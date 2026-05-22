package com.sreer.rainfallmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sreer.rainfallmonitor.mqtt.MqttService

/**
 * Starts the MQTT service when the device boots.
 *
 * Listens for:
 *   - BOOT_COMPLETED        — standard Android boot broadcast
 *   - QUICKBOOT_POWERON     — HTC/Motorola fast-boot variant
 *   - LOCKED_BOOT_COMPLETED — direct boot (encrypted storage ready)
 *
 * This is the PRIMARY mechanism for starting the service — it ensures
 * the service begins running automatically without the user having to
 * open the app after a reboot.
 *
 * connectedDevice foreground services CAN start from BOOT_COMPLETED
 * on Android 15 (unlike dataSync which is restricted).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot broadcast received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                startMqttService(context)
            }
        }
    }

    private fun startMqttService(context: Context) {
        try {
            val serviceIntent = Intent(context, MqttService::class.java)
            context.startForegroundService(serviceIntent)
            Log.i(TAG, "MqttService started from boot receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MqttService from boot: ${e.message}", e)
        }
    }
}
