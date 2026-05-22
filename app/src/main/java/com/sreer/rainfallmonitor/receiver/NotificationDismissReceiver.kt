package com.sreer.rainfallmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sreer.rainfallmonitor.mqtt.MqttService

/**
 * Handles the rare edge case where the user swipes away the
 * foreground service notification.
 *
 * Even though our notification is set to ongoing=true, some OEM
 * launchers (including Motorola's) allow users to dismiss it.
 * When that happens, the service is still running but has no
 * visible notification — which violates Android's foreground
 * service contract and can cause the OS to kill it.
 *
 * This receiver catches the notification dismissal and immediately
 * re-posts the notification by calling startForeground() again.
 * The SERVICE stays running — only the notification disappears briefly.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifDismissReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "Notification dismissed — restarting service to re-post")

        try {
            // Starting the service again triggers onStartCommand(),
            // which is a no-op for MQTT but the startForeground() call
            // in onCreate/re-arm will re-post the notification.
            // Since the service is already running, Android just delivers
            // a new Intent — it doesn't create a second instance.
            val serviceIntent = Intent(context, MqttService::class.java).apply {
                action = MqttService.ACTION_REPOST_NOTIFICATION
            }
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service after notification dismiss: ${e.message}", e)
        }
    }
}
