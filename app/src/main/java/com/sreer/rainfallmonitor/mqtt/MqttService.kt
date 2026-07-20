package com.sreer.rainfallmonitor.mqtt

import android.app.AlarmManager
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
import com.sreer.rainfallmonitor.AppConstants
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
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
        private const val PREF_ESP32_OFFLINE_NOTIFIED = "esp32_offline_notified"

        // ── Persisted consecutive-null tracking ───────────────────────
        private const val PREF_PRESSURE_NULL_COUNT = "pressure_null_count"
        private const val PREF_TEMP_NULL_COUNT = "temp_null_count"
        private const val PREF_HUMIDITY_NULL_COUNT = "humidity_null_count"
        private const val PREF_PRESSURE_ACKED = "pressure_acked"
        private const val PREF_TEMP_ACKED = "temp_acked"
        private const val PREF_HUMIDITY_ACKED = "humidity_acked"
        private const val PREF_LAST_FRESH_TS = "last_fresh_timestamp"

        /** Intent action for the sensor failure acknowledge button. */
        const val ACTION_ACKNOWLEDGE_FAILURE = "com.sreer.rainfallmonitor.ACTION_ACK_FAILURE"

        /**
         * Data staleness threshold — sourced from [AppConstants].
         * 15 min = 1.5× the ESP32's 10-min heartbeat interval.
         * Also used as the gap detection threshold for "Since X ago".
         */
        private val STALE_THRESHOLD_MS = AppConstants.STALE_THRESHOLD_MS

        /** Backoff timing constants. */
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val JITTER_MAX_MS = 2_000L

        /** MQTT keepalive interval in seconds. */
        private const val KEEPALIVE_SECONDS = 60

        /** Persistent session expiry: 24 hours. */
        private const val SESSION_EXPIRY_SECONDS = 86_400L

        /** Watchdog alarm interval: 15 minutes. */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        /** Watchdog PendingIntent request code. */
        private const val WATCHDOG_REQUEST_CODE = 1001

        /** Intent action for re-posting the foreground notification. */
        const val ACTION_REPOST_NOTIFICATION = "com.sreer.rainfallmonitor.ACTION_REPOST"

        /** Maps internal sensor keys to user-facing display names. */
        private val SENSOR_DISPLAY_NAMES = mapOf(
            "pressure" to "Pressure",
            "temperature" to "Temperature",
            "humidity" to "Humidity"
        )

        /**
         * Schedules the watchdog alarm to fire in ~15 minutes.
         * Called by:
         *   - MqttService.onCreate() (self-healing loop — arms on every start)
         *   - ServiceWatchdogReceiver (re-arms after each check)
         */
        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, com.sreer.rainfallmonitor.receiver.ServiceWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS

            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
                Log.d(TAG, "Watchdog alarm scheduled for ${WATCHDOG_INTERVAL_MS / 60000} min from now")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog alarm: ${e.message}", e)
            }
        }
    }

    // ── Service-scoped coroutine scope (cancelled in onDestroy) ────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── MQTT client (created ONCE in onCreate, REUSED across reconnections) ──
    private lateinit var mqttClient: Mqtt5AsyncClient
    private var connectJob: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var currentBackoff = INITIAL_BACKOFF_MS
    @Volatile private var isReconnecting = false

    // ── Network monitoring ────────────────────────────────────────────
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var hasNetwork = false

    // ── Database & preferences ────────────────────────────────────────
    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())

    // ── Sensor failure notification lock ──────────────────────────────
    // Synchronises access to notification posting and acknowledge handling.
    // The acknowledge state is persisted in SharedPrefs and checked
    // directly rather than cached in a Boolean field.
    private val notificationLock = Any()

    /** One-shot guard: becomes true after the first alert with sound fires
     *  for the current failure episode. Prevents re-sound on every heartbeat.
     *  Reset on acknowledge, full recovery, disconnect, or startup. */
    @Volatile private var alertPostedForEpisode = false

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        db = AppDatabase.getInstance(applicationContext)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // Restore consecutive-null tracking from SharedPrefs — survives restarts
        RainfallRepository.initConsecutiveNullCounts(
            prefs.getInt(PREF_PRESSURE_NULL_COUNT, 0),
            prefs.getInt(PREF_TEMP_NULL_COUNT, 0),
            prefs.getInt(PREF_HUMIDITY_NULL_COUNT, 0)
        )
        val lastFreshTs = prefs.getLong(PREF_LAST_FRESH_TS, 0L)
        if (lastFreshTs > 0L) {
            RainfallRepository.initLastFreshTimestamp(lastFreshTs)
        }

        // ── Startup notification re-post ──────────────────────────
        // After a reboot, Android clears all notifications. If any sensor
        // was failing before the restart, re-post the notification now so
        // the user isn't left uninformed.
        val startupFailing = listOf(
            "pressure" to prefs.getInt(PREF_PRESSURE_NULL_COUNT, 0),
            "temperature" to prefs.getInt(PREF_TEMP_NULL_COUNT, 0),
            "humidity" to prefs.getInt(PREF_HUMIDITY_NULL_COUNT, 0)
        ).filter { it.second >= 2 }
        if (startupFailing.isNotEmpty()) {
            // Always silent re-post — the user saw this before reboot.
            // Using the ALERT channel would play sound even with forceSound=false
            // because notify() on a HIGH-importance channel with no existing
            // notification to update is treated as a fresh post.
            val pairs = startupFailing.map { Pair(SENSOR_DISPLAY_NAMES[it.first]!!, it.second) }
            com.sreer.rainfallmonitor.notification.NotificationHelper
                .showSensorFailureSilent(this, pairs)
            alertPostedForEpisode = true
            Log.i(TAG, "Re-posted sensor failure notification from persisted state: ${startupFailing}")
        }

        // Bootstrap Repository from DB — populates the dashboard with the
        // last known state BEFORE any MQTT messages arrive. Without this,
        // the dashboard shows "Waiting for data..." until a FRESH message
        // arrives, even if the DB has hundreds of readings.
        serviceScope.launch {
            try {
                val latest = db.rainfallReadingDao().getLatestReadingOnce()
                if (latest != null) {
                    // Guard: only bootstrap if no fresh data has arrived yet.
                    // This prevents a race where a fast MQTT message updates
                    // the Repository BEFORE this DB read finishes, and then
                    // the bootstrap overwrites the fresh value with an older one.
                    if (RainfallRepository.lastUpdated.value == 0L) {
                        // Process all readings for this datestamp chronologically
                        // so the Repository correctly determines stateStartedAt
                        // from the actual state transition history, rather than
                        // just the latest reading.
                        val readings = db.rainfallReadingDao()
                            .getReadingsForDateOnce(latest.datestamp)
                        var acceptedCount = 0
                        for (reading in readings) {
                            val result = RainfallRepository.updateReading(
                                rate = reading.hourlyRate,
                                timestamp = reading.timestamp,
                                hasDataGap = true,
                                isStale = true,
                                pressure = reading.pressure,
                                temperature = reading.temperature,
                                humidity = reading.humidity
                            )
                            if (result.accepted) acceptedCount++
                        }
                        Log.i(TAG, "Bootstrapped $acceptedCount/${readings.size} readings from DB")
                    } else {
                        Log.d(TAG, "Skipping bootstrap — fresh data already in Repository")
                    }
                } else {
                    Log.d(TAG, "No readings in DB — nothing to bootstrap")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bootstrap from DB", e)
            }
        }

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

        // Build the MQTT client ONCE — reused across all reconnections.
        // This preserves the HiveMQ session state (message queue).
        val clientId = getOrCreateClientId()
        mqttClient = Mqtt5Client.builder()
            .identifier(clientId)
            .serverHost(BuildConfig.MQTT_BROKER_HOST)
            .serverPort(BuildConfig.MQTT_BROKER_PORT)
            .sslWithDefaultConfig()
            .addDisconnectedListener {
                Log.w(TAG, "Disconnected listener fired — updating state")
                RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                updateNotification("Connection lost — reconnecting...")
                attemptConnection()
            }
            .buildAsync()
        Log.i(TAG, "MQTT client built with ID: $clientId")

        // Register global message handler BEFORE connecting.
        // This is CRITICAL: when we reconnect with cleanStart=false, HiveMQ
        // delivers queued messages IMMEDIATELY after CONNACK — before
        // subscribeToTopic() can register a per-subscription callback.
        // Without this, queued messages from overnight gaps are silently lost.
        // See: https://hivemq.com/blog/hivemq-mqtt-client-best-practices/
        val topic = BuildConfig.MQTT_TOPIC_STATUS
        mqttClient.publishes(com.hivemq.client.mqtt.MqttGlobalPublishFilter.ALL) { publish ->
            val payload = String(publish.payloadAsBytes)
            Log.d(TAG, "Received on [${publish.topic}]: $payload")
            serviceScope.launch {
                processMessage(payload)
            }
        }
        Log.d(TAG, "Global publish handler registered")

        // Arm the watchdog alarm (self-healing loop)
        scheduleWatchdog(this)

        // Register network callback (Layer 2)
        registerNetworkCallback()

        // Periodic ESP32 health check — fires a notification if the ESP32
        // hasn't sent data for > 15 min while we're connected to HiveMQ.
        // Checks every 30 seconds (matching the UI ticker in MainActivity)
        // so the notification appears at the same time as the in-app banner.
        // The stale check (age > 15 min) prevents premature firing.
        // Uses a SharedPrefs flag to fire only ONCE per offline event —
        // cleared when fresh data arrives in processMessage().
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000) // 30 seconds — same as UI ticker

                try {
                    val connState = RainfallRepository.connectionState.value
                    val lastUpdated = RainfallRepository.lastUpdated.value
                    val alreadyNotified = prefs.getBoolean(PREF_ESP32_OFFLINE_NOTIFIED, false)

                    if (connState == ConnectionState.CONNECTED
                        && lastUpdated != 0L
                        && !alreadyNotified
                    ) {
                        val age = System.currentTimeMillis() - lastUpdated
                        if (age > STALE_THRESHOLD_MS) {
                            val minutesAgo = age / 60_000
                            Log.w(TAG, "ESP32 offline detected — no data for ${minutesAgo}min")
                            com.sreer.rainfallmonitor.notification.NotificationHelper
                                .showEsp32OfflineAlert(this@MqttService, minutesAgo)
                            prefs.edit().putBoolean(PREF_ESP32_OFFLINE_NOTIFIED, true).apply()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ESP32 health check failed", e)
                }
            }
        }

        // On disconnect, dismiss any sensor failure notification that has
        // NOT been acknowledged by the user. Acknowledged ones stay
        // dismissed (silent) and don't reappear until the next reconnect.
        // Also resets the one-shot sound flag so a new failure after
        // reconnect plays an alert sound.
        serviceScope.launch {
            // Track whether we have ever been CONNECTED — only dismiss
            // unacknowledged notifications on disconnection AFTER the
            // connection was established, not during initial startup.
            var wasConnected = false
            RainfallRepository.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    wasConnected = true
                } else if (wasConnected) {
                    synchronized(notificationLock) {
                        val sensors = listOf("pressure", "temperature", "humidity")
                        val unacked = sensors.filter { !isSensorAcknowledged(it) }
                        if (unacked.isNotEmpty()) {
                            com.sreer.rainfallmonitor.notification.NotificationHelper
                                .dismissSensorFailureAlert(this@MqttService)
                            alertPostedForEpisode = false
                            Log.i(TAG, "Connection lost — dismissed unacknowledged sensor failure notification for ${unacked}")
                        }
                    }
                }
            }
        }

        // Start initial connection attempt (Layer 1)
        attemptConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called (flags=$flags, startId=$startId)")

        when (intent?.action) {
            // Handle notification re-post request (from NotificationDismissReceiver)
            ACTION_REPOST_NOTIFICATION -> {
                Log.w(TAG, "Re-posting foreground notification after dismiss")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Connected"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification("Connected"))
                }
            }

            // Handle sensor failure acknowledge button
            ACTION_ACKNOWLEDGE_FAILURE -> {
                Log.i(TAG, "Sensor failure acknowledged via notification action")
                handleAcknowledge()
            }
        }

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

        // Schedule watchdog as safety net — if START_STICKY doesn't restart
        // us within 15 minutes, the watchdog alarm will
        scheduleWatchdog(this)

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
                Log.w(TAG, "Network lost: $network")

                // Don't blindly set hasNetwork=false — another network might
                // still be available (e.g., WiFi drops but mobile data exists)
                val activeNetwork = connectivityManager.activeNetwork
                val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                val stillHasInternet = caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) == true

                if (stillHasInternet) {
                    // Another network is available (e.g., mobile data after WiFi loss).
                    // The old MQTT TCP connection is DEAD (it was bound to the lost
                    // network), but mqttClient.state still shows CONNECTED until
                    // the keepalive times out (~60s). Force a reconnect NOW.
                    hasNetwork = true
                    Log.i(TAG, "Still have internet — forcing reconnect on new network")
                    forceReconnect()
                } else {
                    hasNetwork = false
                    monitorJob?.cancel()
                    RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                    updateNotification("Offline — no internet")
                }
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

    /**
     * Force reconnect — bypasses the CONNECTED state guard.
     *
     * Used when the underlying network changes (e.g., WiFi → mobile data).
     * The MQTT client state may still show CONNECTED (stale TCP socket),
     * but the connection is actually dead. This method kills the old
     * connection and starts fresh.
     */
    private fun forceReconnect() {
        connectJob?.cancel()
        monitorJob?.cancel()
        isReconnecting = false

        connectJob = serviceScope.launch {
            disconnectMqtt()
            reconnectLoop()
        }
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
        if (mqttClient.state == MqttClientState.CONNECTED) {
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
                // Disconnect cleanly if still connected (e.g., stale TCP)
                disconnectMqtt()

                // Reconnect the SAME client (preserves session state)
                Log.i(TAG, "Attempting MQTT connection to ${BuildConfig.MQTT_BROKER_HOST}:${BuildConfig.MQTT_BROKER_PORT}")

                mqttClient.connectWith()
                    .simpleAuth()
                        .username(BuildConfig.MQTT_USERNAME)
                        .password(BuildConfig.MQTT_PASSWORD.toByteArray())
                        .applySimpleAuth()
                    .cleanStart(false)
                    .sessionExpiryInterval(SESSION_EXPIRY_SECONDS)
                    .keepAlive(KEEPALIVE_SECONDS)
                    .send()
                    .await() // Suspend until connected (cancellable)

                // ── SUCCESS ───────────────────────────────────────
                Log.i(TAG, "MQTT connected!")
                currentBackoff = INITIAL_BACKOFF_MS
                isReconnecting = false

                RainfallRepository.updateConnectionState(ConnectionState.CONNECTED)
                updateNotification("Connected")

                // Subscribe to topic
                subscribeToTopic(mqttClient)

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
     *
     * Only sends the SUBSCRIBE packet — the message handler is registered
     * ONCE in onCreate() via client.publishes() (global handler).
     * This avoids two problems:
     *   1. Callback accumulation (N reconnections → N callbacks)
     *   2. Queued messages lost (handler not ready when messages arrive)
     */
    private fun subscribeToTopic(client: Mqtt5AsyncClient) {
        val topic = BuildConfig.MQTT_TOPIC_STATUS

        client.subscribeWith()
            .topicFilter(topic)
            .qos(com.hivemq.client.mqtt.datatypes.MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, error ->
                if (error != null) {
                    Log.e(TAG, "Subscribe failed: ${error.message}")
                    RainfallRepository.updateConnectionState(ConnectionState.OFFLINE)
                    forceReconnect()
                } else {
                    Log.i(TAG, "Subscribed to $topic")
                }
            }

        // Cancel any previous monitor before starting a new one
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
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
     * Does NOT destroy the client — it's reused for reconnection.
     */
    private fun disconnectMqtt() {
        try {
            if (mqttClient.state == MqttClientState.CONNECTED) {
                mqttClient.disconnect().get(3, TimeUnit.SECONDS)
                Log.d(TAG, "MQTT disconnected cleanly")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during MQTT disconnect: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Processes an incoming MQTT message from the ESP32.
     *
     * Pipeline:
     *   1. Parse JSON → extract rate + timestamp + sensor values
     *   2. Save to Room DB (ALWAYS — every message is a historical record)
     *   3. Compute staleness — stale messages still update the dashboard
     *      (with grey [S] styling), but do NOT trigger notifications
     *   4. Detect data gap — if gap > 15 min between ESP32 timestamps,
     *      flag it so the Repository resets "Since X ago"
     *   5. Update Repository — BOTH fresh AND stale messages update the
     *      display (dashboard values, timestamps, rain state). Fresh messages
     *      also update the consecutive-null tracking for Feature 1.
     *   6. Clear ESP32 offline notification (fresh data only)
     *   7. Sensor failure detection — check if any sensor has 2 consecutive
     *      nulls among fresh messages (Feature 1)
     *   8. Rain transition detection (fresh messages only)
     */
    private suspend fun processMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            val rate = json.getDouble("rate").toFloat()
            val timestamp = json.getLong("timestamp")

            // Derive datestamp from ESP32 timestamp
            val datestamp = dateFormatter.format(Instant.ofEpochMilli(timestamp))

            // Extract pressure, temperature, humidity (ESP32 sends null on sensor failure)
            val pressure = json.optDouble("pressure", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
            val temperature = json.optDouble("temperature", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
            val humidity = json.optDouble("humidity", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }

            // 1. Save to Room DB (ALWAYS — stale or not)
            val reading = RainfallReading(
                hourlyRate = rate,
                timestamp = timestamp,
                datestamp = datestamp,
                pressure = pressure,
                temperature = temperature,
                humidity = humidity
            )
            db.rainfallReadingDao().insert(reading)
            Log.d(TAG, "Saved reading: rate=$rate, pressure=$pressure, temp=$temperature, hum=$humidity, time=$timestamp, date=$datestamp")

            // 2. Stale message check — compute staleness but do NOT return early.
            //    Stale messages update the dashboard (grey [S] styling) but
            //    do NOT trigger any notifications.
            val now = System.currentTimeMillis()
            val messageAge = now - timestamp
            val isStale = messageAge > STALE_THRESHOLD_MS

            if (isStale) {
                Log.d(TAG, "Stale message (${messageAge / 60000}min old) — updating dashboard with stale marker")
            }

            // 3. Detect data gap — if the time since the last Repository
            //    update exceeds the stale threshold, we can't claim the
            //    current rain/no-rain state has been continuous. It might
            //    have rained (or stopped) during the gap.
            val lastKnownTimestamp = RainfallRepository.lastUpdated.value
            val hasDataGap = lastKnownTimestamp != 0L &&
                    (timestamp - lastKnownTimestamp) > STALE_THRESHOLD_MS
            if (hasDataGap) {
                Log.i(TAG, "Data gap detected: ${(timestamp - lastKnownTimestamp) / 60000}min since last reading")
            }

            // 4. Update Repository — BOTH fresh AND stale messages update
            //    dashboard display values. Only fresh messages change the
            //    consecutive-null tracking (isStale flag guards this).
            val result = RainfallRepository.updateReading(
                rate = rate,
                timestamp = timestamp,
                hasDataGap = hasDataGap,
                isStale = isStale,
                pressure = pressure,
                temperature = temperature,
                humidity = humidity
            )
            if (!result.accepted) {
                Log.w(TAG, "MQTT message rejected (stale/duplicate): rate=$rate, ts=$timestamp")
            }

            // ── Everything below here is for FRESH messages only ──────
            if (isStale) return

            // 5. Clear ESP32 offline state — we just got fresh data!
            //    Dismiss any showing notification and reset the anti-spam flag
            //    so the next offline event triggers a new notification.
            if (prefs.getBoolean(PREF_ESP32_OFFLINE_NOTIFIED, false)) {
                prefs.edit().putBoolean(PREF_ESP32_OFFLINE_NOTIFIED, false).apply()
                com.sreer.rainfallmonitor.notification.NotificationHelper
                    .dismissEsp32OfflineAlert(this@MqttService)
                Log.i(TAG, "ESP32 back online — dismissed offline notification")
            }

            // 6. Sensor failure detection — check consecutive null
            //    counts from the UpdateResult (snapshotted inside the
            //    synchronized updateReading call).
            //    A count >= 2 means the sensor has failed.
            //    Uses display names (capitalized) for the notification body.
            //    Handles race ordering: recovery cleared BEFORE posting so
            //    that a still-failing sensor's alert is never dismissed.
            synchronized(notificationLock) {
                val counts = mapOf(
                    "pressure" to result.consecutivePressureNull,
                    "temperature" to result.consecutiveTemperatureNull,
                    "humidity" to result.consecutiveHumidityNull
                )

                // ── Handle recovery FIRST ──
                // Clear ack flags for sensors that recovered (count == 0
                // and were acknowledged). Save whether ANY sensor recovered
                // so we know whether to dismiss at the end.
                val recovered = listOf("pressure", "temperature", "humidity")
                    .filter { counts[it] == 0 && isSensorAcknowledged(it) }
                var anyRecovered = false
                if (recovered.isNotEmpty()) {
                    recovered.forEach { setSensorAcknowledged(it, false) }
                    anyRecovered = true
                    Log.i(TAG, "Acknowledged sensor recovered: ${recovered}")
                }

                // ── Determine current failure state ──
                val currentlyFailing = counts.filter { it.value >= 2 }
                val stillFailing = currentlyFailing.isNotEmpty()
                val unackedAmongFailing = currentlyFailing.keys
                    .filter { !isSensorAcknowledged(it) }

                // ── Post or update notification ──
                if (stillFailing) {
                    // Build countPairs with DISPLAY names (capitalized)
                    val countPairs = currentlyFailing.map {
                        Pair(SENSOR_DISPLAY_NAMES[it.key]!!, it.value)
                    }

                    if (unackedAmongFailing.isNotEmpty()) {
                        // There is at least one unacknowledged failing sensor → post on ALERT channel
                        if (!alertPostedForEpisode) {
                            // First time this episode: play sound
                            com.sreer.rainfallmonitor.notification.NotificationHelper
                                .showSensorFailureAlert(this@MqttService, countPairs, forceSound = true)
                            alertPostedForEpisode = true
                            Log.w(TAG, "Sensor failure notification (with sound): ${unackedAmongFailing}")
                        } else {
                            // Already notified: update silently, no re-sound
                            com.sreer.rainfallmonitor.notification.NotificationHelper
                                .showSensorFailureAlert(this@MqttService, countPairs, forceSound = false)
                            Log.d(TAG, "Sensor failure notification (silent update): ${unackedAmongFailing}")
                        }
                    } else {
                        // All failing sensors are acknowledged → SILENT channel
                        com.sreer.rainfallmonitor.notification.NotificationHelper
                            .showSensorFailureSilent(this@MqttService, countPairs)
                        Log.d(TAG, "Silent failure update for acknowledged: ${currentlyFailing.keys}")
                    }
                } else if (anyRecovered) {
                    // Every sensor recovered and no sensors are failing → dismiss
                    com.sreer.rainfallmonitor.notification.NotificationHelper
                        .dismissSensorFailureAlert(this@MqttService)
                    alertPostedForEpisode = false
                    Log.i(TAG, "All sensors recovered — dismissed failure notification")
                } else if (!stillFailing && alertPostedForEpisode) {
                    // Natural recovery without ack — dismiss stale notification
                    com.sreer.rainfallmonitor.notification.NotificationHelper
                        .dismissSensorFailureAlert(this@MqttService)
                    alertPostedForEpisode = false
                    Log.i(TAG, "Sensors recovered naturally — dismissed stale notification")
                }

                // ── Persist consecutive-null counts and fresh timestamp ──
                // These survive service restarts so the counter doesn't reset.
                // Inside synchronized block to prevent interleaving races
                // when multiple processMessage coroutines run concurrently.
                prefs.edit()
                    .putInt(PREF_PRESSURE_NULL_COUNT, result.consecutivePressureNull)
                    .putInt(PREF_TEMP_NULL_COUNT, result.consecutiveTemperatureNull)
                    .putInt(PREF_HUMIDITY_NULL_COUNT, result.consecutiveHumidityNull)
                    .putLong(PREF_LAST_FRESH_TS, timestamp)
                    .apply()
            }

            // 7. Rain transition detection (fresh messages only)
            val previousRate = prefs.getFloat(PREF_LAST_RATE, 0f)
            val wasRaining = previousRate > 0f
            val isRaining = rate > 0f

            // Save current rate for next comparison
            prefs.edit().putFloat(PREF_LAST_RATE, rate).apply()

            if (isRaining && (!wasRaining || hasDataGap)) {
                Log.i(TAG, "Rain alert: rate=$rate mm/hr (gap=$hasDataGap, prev=$previousRate)")
                com.sreer.rainfallmonitor.notification.NotificationHelper
                    .showRainAlert(this@MqttService, rate)
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

        // deleteIntent fires when the notification is dismissed (swiped away)
        // This re-posts it immediately so the service stays foreground
        val dismissIntent = Intent(this, com.sreer.rainfallmonitor.receiver.NotificationDismissReceiver::class.java)
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, 0, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, RainfallApp.MQTT_CHANNEL_ID)
            .setContentTitle("Rainfall Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
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

    // ═══════════════════════════════════════════════════════════════════
    // SENSOR FAILURE ACKNOWLEDGE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Checks whether the user has acknowledged a failure for the given sensor.
     */
    private fun isSensorAcknowledged(sensor: String): Boolean {
        return when (sensor) {
            "pressure" -> prefs.getBoolean(PREF_PRESSURE_ACKED, false)
            "temperature" -> prefs.getBoolean(PREF_TEMP_ACKED, false)
            "humidity" -> prefs.getBoolean(PREF_HUMIDITY_ACKED, false)
            else -> false
        }
    }

    /**
     * Persists the acknowledge flag for a sensor.
     */
    private fun setSensorAcknowledged(sensor: String, acked: Boolean) {
        val key = when (sensor) {
            "pressure" -> PREF_PRESSURE_ACKED
            "temperature" -> PREF_TEMP_ACKED
            "humidity" -> PREF_HUMIDITY_ACKED
            else -> return
        }
        prefs.edit().putBoolean(key, acked).apply()
        Log.i(TAG, "Sensor ${sensor} acknowledge set to $acked")
    }

    /**
     * Handles the acknowledge action from the notification button.
     * Moves all currently failing sensors to the "acknowledged" state,
     * which causes subsequent failure notifications to post silently.
     */
    private fun handleAcknowledge() {
        synchronized(notificationLock) {
            val consecutive = mapOf(
                "pressure" to RainfallRepository.consecutiveFreshPressureNull.value,
                "temperature" to RainfallRepository.consecutiveFreshTemperatureNull.value,
                "humidity" to RainfallRepository.consecutiveFreshHumidityNull.value
            )
            val failing = consecutive.filter { it.value >= 2 }
            failing.keys.forEach { setSensorAcknowledged(it, true) }
            alertPostedForEpisode = false  // allow sound if a NEW sensor fails later

            if (failing.isNotEmpty()) {
                val countPairs = failing.map { Pair(SENSOR_DISPLAY_NAMES[it.key]!!, it.value) }
                com.sreer.rainfallmonitor.notification.NotificationHelper
                    .dismissSensorFailureAlert(this@MqttService)
                com.sreer.rainfallmonitor.notification.NotificationHelper
                    .showSensorFailureSilent(this@MqttService, countPairs)
                Log.i(TAG, "Acknowledge processed for: ${failing.keys} — moved to silent")
            }
        }
    }
}
