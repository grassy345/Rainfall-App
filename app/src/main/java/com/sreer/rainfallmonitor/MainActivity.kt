package com.sreer.rainfallmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sreer.rainfallmonitor.databinding.ActivityMainBinding
import com.sreer.rainfallmonitor.mqtt.MqttService

/**
 * Main Activity — the single-screen dashboard.
 *
 * Responsibilities (Step 4):
 *   1. Start MqttService as a backup (in case boot receiver didn't fire)
 *   2. Request POST_NOTIFICATIONS permission (Android 13+)
 *   3. Prompt battery optimization whitelisting (one-time)
 *
 * The dashboard UI (Step 5) will be built on top of this foundation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "app_prefs"
        private const val PREF_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"
    }

    private lateinit var binding: ActivityMainBinding

    // ── Android 13+ notification permission request ───────────────────
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "Notification permission granted")
            } else {
                Log.w(TAG, "Notification permission denied — rain alerts won't work")
                Toast.makeText(
                    this,
                    "Notification permission denied — rain alerts won't appear",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Start the MQTT service (backup — in case boot receiver didn't fire)
        //    If already running, this is a no-op (Android delivers onStartCommand)
        startMqttService()

        // 2. Request notification permission (Android 13+ / API 33+)
        requestNotificationPermission()

        // 3. Prompt battery optimization whitelisting (one-time)
        promptBatteryOptimization()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERVICE STARTUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts MqttService as a foreground service.
     *
     * This is a BACKUP mechanism — the primary start is via BootReceiver.
     * If the service is already running, Android simply delivers a new
     * Intent to onStartCommand (no duplicate service created).
     */
    private fun startMqttService() {
        try {
            val intent = Intent(this, MqttService::class.java)
            startForegroundService(intent)
            Log.i(TAG, "MqttService start requested from Activity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MqttService: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // NOTIFICATION PERMISSION (Android 13+)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * On Android 13 (API 33) and above, POST_NOTIFICATIONS is a runtime
     * permission. Without it, notifications won't be shown — including
     * the foreground service notification and rain alerts.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // BATTERY OPTIMIZATION WHITELISTING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Prompts the user to whitelist the app from battery optimization.
     *
     * Without this, Android (especially Motorola's ThinkShield) can
     * aggressively kill the service even with connectedDevice type.
     *
     * Shown only once (remembered via SharedPreferences). The user
     * can also re-trigger this from Settings (drawer) in a future step.
     */
    private fun promptBatteryOptimization() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Already prompted? Don't show again.
        if (prefs.getBoolean(PREF_BATTERY_PROMPT_SHOWN, false)) return

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "Already whitelisted from battery optimization")
            prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            return
        }

        // Show explanation dialog, then launch system whitelist intent
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization")
            .setMessage(
                "To keep monitoring rainfall 24/7, this app needs to be excluded " +
                "from battery optimization.\n\n" +
                "On the next screen, select \"Allow\" to prevent Android from " +
                "stopping the background service."
            )
            .setPositiveButton("Continue") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings", e)
                    Toast.makeText(
                        this,
                        "Please manually disable battery optimization in Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setNegativeButton("Not Now") { _, _ ->
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SHOWN, true).apply()
            }
            .setCancelable(false)
            .show()
    }
}
