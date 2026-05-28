package com.sreer.rainfallmonitor.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sreer.rainfallmonitor.MainActivity
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.RainfallApp
import java.util.Locale

/**
 * Handles rain alert notifications.
 *
 * Called by [MqttService] when a rain transition is detected
 * (was "No Rain", now raining). Posts a HIGH-importance notification
 * on the [RainfallApp.ALERT_CHANNEL_ID] channel with sound + vibration.
 *
 * Design decisions:
 *   - Uses a FIXED notification ID (2) so repeated alerts replace each
 *     other instead of stacking. The user only cares about the latest.
 *   - Only fires on the NO_RAIN → RAIN transition, not on intensity
 *     changes (Light → Heavy). This avoids notification spam.
 *   - Stale messages never reach here — the caller (processMessage)
 *     already filters them out before calling this.
 */
object NotificationHelper {

    /** Fixed ID for rain alert notifications. Separate from the service notification (ID 1). */
    private const val ALERT_NOTIFICATION_ID = 2

    /** Fixed ID for ESP32 offline notification. Separate from rain alerts (ID 2). */
    private const val ESP32_OFFLINE_NOTIFICATION_ID = 3

    /**
     * Posts a rain alert notification.
     *
     * @param context Application or service context
     * @param rate    The hourly rainfall rate in mm/hr (always > 0 when called)
     */
    fun showRainAlert(context: Context, rate: Float) {
        val intensity = getIntensityLabel(rate)
        val formattedRate = String.format(Locale.US, "%.2f", rate)

        // Tap → opens MainActivity
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ALERT_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RainfallApp.ALERT_CHANNEL_ID)
            .setContentTitle("🌧️ $intensity Detected")
            .setContentText("Rainfall rate: $formattedRate mm/hr")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)          // Dismiss on tap
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)  // Sound + vibration
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * Posts an ESP32 offline warning notification.
     *
     * Fired ONCE when the ESP32 hasn't sent data for > 15 minutes while
     * the phone is connected to HiveMQ. Dismissed automatically when
     * a fresh reading arrives (via [dismissEsp32OfflineAlert]).
     *
     * @param context Application or service context
     * @param minutesAgo How many minutes since the last reading
     */
    fun showEsp32OfflineAlert(context: Context, minutesAgo: Long) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ESP32_OFFLINE_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RainfallApp.ALERT_CHANNEL_ID)
            .setContentTitle("⚠️ ESP32 May Be Offline")
            .setContentText("No data received for $minutesAgo minutes")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ESP32_OFFLINE_NOTIFICATION_ID, notification)
    }

    /**
     * Dismisses the ESP32 offline notification (if showing).
     * Called when a fresh reading arrives from the ESP32.
     */
    fun dismissEsp32OfflineAlert(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ESP32_OFFLINE_NOTIFICATION_ID)
    }

    /**
     * Derives the rain intensity label from the hourly rate.
     * Duplicates MainViewModel.getIntensityLabel() — intentionally kept
     * separate so the notification module has no dependency on the UI layer.
     */
    private fun getIntensityLabel(rate: Float): String = when {
        rate <= 0f -> "No Rain"
        rate <= 2.5f -> "Light Rain"
        rate <= 7.5f -> "Moderate Rain"
        rate <= 50f -> "Heavy Rain"
        else -> "Violent Rain"
    }
}
