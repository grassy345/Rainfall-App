package com.sreer.rainfallmonitor.mqtt

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.sreer.rainfallmonitor.BuildConfig
import com.sreer.rainfallmonitor.MainActivity
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.RainfallApp
import com.sreer.rainfallmonitor.data.AppDatabase
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.repository.ConnectionState
import com.sreer.rainfallmonitor.repository.RainfallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * MQTT Foreground Service — the "engine" of the app.
 *
 * Maintains a persistent connection to HiveMQ Cloud 24/7, receives
 * rainfall readings from the ESP32, saves them to the Room database,
 * and updates the RainfallRepository's StateFlows for live UI updates.
 *
 * Uses the connectedDevice foreground service type to avoid Android 15's
 * 6-hour dataSync timeout. This service runs indefinitely.
 *
 * Reconnection strategy (3 layers):
 *   Layer 1: Manual exponential backoff with jitter (2s → 60s cap)
 *   Layer 2: ConnectivityManager.NetworkCallback (instant reconnect on network change)
 *   Layer 3: MQTT keepalive ping (detects silent TCP deaths)
 */
class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "mqtt_prefs"
        private const val PREF_CLIENT_ID = "client_id"
        private const val PREF_LAST_RATE = "last_rate"

        /** Messages older than this are considered stale (no notification fired). */
        private const val STALE_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes

        /** Backoff timing constants. */
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val JITTER_MAX_MS = 2_000L

        /** MQTT keepalive interval in seconds. */
        private const val KEEPALIVE_SECONDS = 60

        /** Persistent session expiry: 24 hours. */
        private const val SESSION_EXPIRY_SECONDS = 86_400L
    }

    // ── Service-scoped coroutine scope (cancelled in onDestroy) ────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── MQTT client (created fresh per connection attempt) ─────────────
    private var mqttClient: Mqtt5AsyncClient? = null
    private var connectJob: Job? = null
    private var currentBackoff = INITIAL_BACKOFF_MS
    private var isReconnecting = false

    // ── Network monitoring ────────────────────────────────────────────
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasNetwork = false

    // ── Database & preferences ────────────────────────────────────────
    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        db = AppDatabase.getInstance(applicationContext)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Start as foreground IMMEDIATELY (must be within 5 seconds)
        // Android 14+ (API 34) requires specifying the foreground service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Connecting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        }

        // Register network callback (Layer 2)
        registerNetworkCallback()

        // Start initial connection attempt (Layer 1)
        attemptConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called (flags=$flags, startId=$startId)")
        // If already connected, this is a no-op
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the user swipes the app from Recents.
     * Do NOT call stopSelf() — that would kill the service.
     * Just log it and let the service continue running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "App swiped from Recents — service continues running")
        // Do NOT call super.onTaskRemoved() or stopSelf()
    }

    override fun onDestroy() {
        Log.w(TAG, "Service being destroyed")

        // Clean up network callback
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback", e)
            }
        }

        // Disconnect MQTT client
        disconnectMqtt()

        // Update connection state
        RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)

        // Cancel all coroutines
        serviceScope.cancel()

        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════
    // NETWORK MONITORING (Layer 2)
    // ═══════════════════════════════════════════════════════════════════

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available — triggering reconnect")
                hasNetwork = true
                // Immediately attempt reconnection when network comes back
                attemptConnection()
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                hasNetwork = false
                RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                updateNotification("Offline — no internet")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        // Check current network state
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        hasNetwork = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    // ═══════════════════════════════════════════════════════════════════
    // MQTT CONNECTION (Layer 1 — manual backoff + jitter)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts a connection attempt if not already connecting/connected.
     */
    private fun attemptConnection() {
        // Guard: don't start multiple concurrent connection attempts
        if (isReconnecting) {
            Log.d(TAG, "Already reconnecting — skipping")
            return
        }

        // Guard: check if already connected
        val client = mqttClient
        if (client != null && client.state == MqttClientState.CONNECTED) {
            Log.d(TAG, "Already connected — skipping")
            return
        }

        isReconnecting = true
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            reconnectLoop()
        }
    }

    /**
     * Reconnection loop with exponential backoff + jitter.
     * Retries FOREVER (capped at 60-second intervals).
     * Only stops when: connected, no network, or service destroyed.
     */
    private suspend fun reconnectLoop() {
        currentBackoff = INITIAL_BACKOFF_MS

        while (true) {
            // Skip if no network
            if (!hasNetwork) {
                Log.d(TAG, "No network — waiting for NetworkCallback")
                RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                updateNotification("Offline — no internet")
                isReconnecting = false
                return
            }

            RainfallRepository.updateConnectionState(ConnectionState.CONNECTING)
            updateNotification("Connecting...")

            try {
                // Disconnect previous client if it exists
                disconnectMqtt()

                // Create fresh client
                val clientId = getOrCreateClientId()
                val newClient = Mqtt5Client.builder()
                    .identifier(clientId)
                    .serverHost(BuildConfig.MQTT_BROKER_HOST)
                    .serverPort(BuildConfig.MQTT_BROKER_PORT)
                    .sslWithDefaultConfig()
                    .buildAsync()

                mqttClient = newClient

                // Connect with persistent session
                Log.i(TAG, "Attempting MQTT connection to ${BuildConfig.MQTT_BROKER_HOST}:${BuildConfig.MQTT_BROKER_PORT}")

                newClient.connectWith()
                    .simpleAuth()
                        .username(BuildConfig.MQTT_USERNAME)
                        .password(BuildConfig.MQTT_PASSWORD.toByteArray())
                        .applySimpleAuth()
                    .cleanStart(false)
                    .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                    .keepAlive(KEEPALIVE_SECONDS)
                    .send()
                    .get() // Block until connected (we're on Dispatchers.IO)

                // ── SUCCESS ───────────────────────────────────────
                Log.i(TAG, "MQTT connected!")
                currentBackoff = INITIAL_BACKOFF_MS
                isReconnecting = false

                RainfallRepository.updateConnectionState(ConnectionState.CONNECTED)
                updateNotification("Connected")

                // Subscribe to topic
                subscribeToTopic(newClient)

                return // Exit the reconnect loop — we're connected

            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed: ${e.message}")

                // Calculate next backoff with jitter
                val jitter = (Math.random() * JITTER_MAX_MS).toLong()
                val waitTime = currentBackoff + jitter
                Log.d(TAG, "Retrying in ${waitTime}ms (backoff=${currentBackoff}ms, jitter=${jitter}ms)")

                delay(waitTime)

                // Increase backoff (exponential, capped at MAX)
                currentBackoff = min(currentBackoff * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /**
     * Subscribe to the rainfall/status topic after connecting.
     * The callback processes every incoming message.
     */
    private fun subscribeToTopic(client: Mqtt5AsyncClient) {
        val topic = BuildConfig.MQTT_TOPIC_STATUS

        client.subscribeWith()
            .topicFilter(topic)
            .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                val payload = String(publish.payloadAsBytes)
                Log.d(TAG, "Received on [$topic]: $payload")
                serviceScope.launch {
                    processMessage(payload)
                }
            }
            .send()
            .whenComplete { _, error ->
                if (error != null) {
                    Log.e(TAG, "Subscribe failed: ${error.message}")
                } else {
                    Log.i(TAG, "Subscribed to $topic")
                }
            }

        // Also set a disconnect listener to trigger reconnection
        // HiveMQ client's state can be polled, but we use a health-check
        // coroutine to detect connection loss from keepalive timeouts
        serviceScope.launch {
            monitorConnection(client)
        }
    }

    /**
     * Periodically checks if the MQTT client is still connected.
     * Catches silent TCP deaths that the keepalive ping detects
     * but that don't trigger a callback directly.
     */
    private suspend fun monitorConnection(client: Mqtt5AsyncClient) {
        while (true) {
            delay(15_000L) // Check every 15 seconds
            if (client.state != MqttClientState.CONNECTED) {
                Log.w(TAG, "Connection lost (detected by monitor)")
                RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                updateNotification("Connection lost — reconnecting...")
                attemptConnection()
                return
            }
        }
    }

    /**
     * Safely disconnect the current MQTT client.
     */
    private fun disconnectMqtt() {
        try {
            val client = mqttClient
            if (client != null && client.state == MqttClientState.CONNECTED) {
                client.disconnect().get()
                Log.d(TAG, "MQTT disconnected cleanly")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during MQTT disconnect: ${e.message}")
        }
        mqttClient = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Processes an incoming MQTT message from the ESP32.
     *
     * Pipeline:
     *   1. Parse JSON → extract rate + timestamp
     *   2. Save to Room DB (ALWAYS, even stale)
     *   3. Update Repository StateFlows (ALWAYS)
     *   4. Check stale (> 30 min) — skip notification if stale
     *   5. Check rain transition — fire notification if fresh
     */
    private suspend fun processMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val rate = json.getDouble("rate").toFloat()
            val timestamp = json.getLong("timestamp")

            // Derive datestamp from ESP32 timestamp
            val datestamp = dateFormat.format(Date(timestamp))

            // 1. Save to Room DB (ALWAYS — stale or not)
            val reading = RainfallReading(
                hourlyRate = rate,
                timestamp = timestamp,
                datestamp = datestamp
            )
            db.rainfallReadingDao().insert(reading)
            Log.d(TAG, "Saved reading: rate=$rate, time=$timestamp, date=$datestamp")

            // 2. Update Repository StateFlows (ALWAYS)
            RainfallRepository.updateReading(rate, timestamp)

            // 3. Stale message check (for NOTIFICATIONS ONLY)
            val now = System.currentTimeMillis()
            val messageAge = now - timestamp
            val isStale = messageAge > STALE_THRESHOLD_MS

            if (isStale) {
                Log.d(TAG, "Stale message (${messageAge / 60000}min old) — saved but no notification")
                return // Saved to DB + updated UI, but skip notification logic
            }

            // 4. Rain transition detection (fresh messages only)
            val previousRate = prefs.getFloat(PREF_LAST_RATE, 0f)
            val wasRaining = previousRate > 0f
            val isRaining = rate > 0f

            // Save current rate for next comparison
            prefs.edit().putFloat(PREF_LAST_RATE, rate).apply()

            if (!wasRaining && isRaining) {
                Log.i(TAG, "Rain transition detected! rate=$rate mm/hr")
                // Notification will be handled by NotificationHelper in Step 6
                // For now, just log it
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to process message: $payload", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLIENT ID MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Returns a stable client ID, creating one if this is the first launch.
     * The same client ID is reused across app restarts to maintain the
     * HiveMQ persistent session (message queuing).
     */
    private fun getOrCreateClientId(): String {
        val existing = prefs.getString(PREF_CLIENT_ID, null)
        if (existing != null) return existing

        val newId = "rainfall-android-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(PREF_CLIENT_ID, newId).apply()
        Log.i(TAG, "Created new MQTT client ID: $newId")
        return newId
    }

    // ═══════════════════════════════════════════════════════════════════
    // NOTIFICATION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Builds the persistent foreground notification.
     */
    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RainfallApp.MQTT_CHANNEL_ID)
            .setContentTitle("Rainfall Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Updates the persistent notification text without recreating it.
     */
    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
