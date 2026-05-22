package com.sreer.rainfallmonitor.receiver

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sreer.rainfallmonitor.mqtt.MqttService

/**
 * Watchdog receiver — the "safety net behind the safety net."
 *
 * Fires every ~15 minutes via setExactAndAllowWhileIdle().
 * Checks if MqttService is running. If not, restarts it.
 *
 * This covers the rare case where both START_STICKY and
 * the connectedDevice exemption fail to keep the service alive
 * (e.g., aggressive OEM battery management on Motorola).
 *
 * The alarm is:
 *   - Armed by MqttService.onCreate() (self-healing loop)
 *   - Re-armed by this receiver after every check
 *   - Pierces Doze mode (setExactAndAllowWhileIdle)
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WatchdogReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Watchdog alarm fired — checking service status")

        if (!isServiceRunning(context)) {
            Log.w(TAG, "MqttService NOT running — restarting!")
            try {
                val serviceIntent = Intent(context, MqttService::class.java)
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "MqttService restarted by watchdog")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart MqttService: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "MqttService is running — all good")
        }

        // Re-arm the watchdog alarm for the next check
        MqttService.scheduleWatchdog(context)
    }

    /**
     * Checks if MqttService is currently running.
     *
     * Uses ActivityManager.getRunningServices() — while deprecated for
     * third-party app discovery, it still works reliably for checking
     * your OWN services within the same package.
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MqttService::class.java.name
        }
    }
}
