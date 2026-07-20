package com.sreer.rainfallmonitor.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sreer.rainfallmonitor.MainActivity
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.RainfallApp
import com.sreer.rainfallmonitor.mqtt.MqttService
import java.util.Locale

object NotificationHelper {

    private const val ALERT_NOTIFICATION_ID = 2
    private const val ESP32_OFFLINE_NOTIFICATION_ID = 3
    private const val SENSOR_NA_NOTIFICATION_ID = 4
    private const val ACK_REQUEST_CODE = 2001

    // ── Rain alert ─────────────────────────────────────────────────────

    fun showRainAlert(context: Context, rate: Float) {
        val intensity = getIntensityLabel(rate)
        val formattedRate = String.format(Locale.US, "%.2f", rate)

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
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    // ── ESP32 offline ──────────────────────────────────────────────────

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

    fun dismissEsp32OfflineAlert(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ESP32_OFFLINE_NOTIFICATION_ID)
    }

    // ── Sensor failure ─────────────────────────────────────────────────

    /**
     * Builds a grammar-aware body text from a list of (sensorName, count) pairs.
     *
     * Examples:
     *   [("Pressure", 3)] → "Pressure sensor has failed 3 times in a row"
     *   [("Pressure", 3), ("Temperature", 2)] → "Pressure (3), Temperature (2) sensors have failed"
     */
    private fun buildFailureBody(failedSensors: List<Pair<String, Int>>): String {
        return when (failedSensors.size) {
            1 -> {
                val (name, count) = failedSensors[0]
                "$name sensor has failed $count times in a row"
            }
            2 -> {
                val parts = failedSensors.joinToString(", ") { (name, count) ->
                    "$name ($count)"
                }
                "$parts sensors have failed"
            }
            else -> {
                val parts = failedSensors.joinToString(", ") { (name, count) ->
                    "$name ($count)"
                }
                "$parts sensors have failed"
            }
        }
    }

    /**
     * Posts a sensor failure notification on the ALERT channel.
     *
     * @param forceSound When true, cancels the old notification first so the system
     *   treats the new one as a fresh alert (plays sound + vibration).
     *   When false, only notifies (updates the notification in place, no sound).
     */
    fun showSensorFailureAlert(context: Context, failedSensors: List<Pair<String, Int>>, forceSound: Boolean = true) {
        val body = buildFailureBody(failedSensors)
        val manager = context.getSystemService(NotificationManager::class.java)

        if (forceSound) {
            // Cancel first so the next notify is treated as a brand new alert → plays sound
            manager.cancel(SENSOR_NA_NOTIFICATION_ID)
        }

        val ackIntent = Intent(context, MqttService::class.java).apply {
            action = MqttService.ACTION_ACKNOWLEDGE_FAILURE
        }
        val ackPendingIntent = PendingIntent.getService(
            context, ACK_REQUEST_CODE, ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, SENSOR_NA_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RainfallApp.ALERT_CHANNEL_ID)
            .setContentTitle("⚠️ Sensor Failure Detected")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Acknowledge", ackPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        manager.notify(SENSOR_NA_NOTIFICATION_ID, notification)
    }

    /**
     * Posts a sensor failure notification on the SILENT channel (no sound).
     * Called after the user has acknowledged, or there are no unacknowledged sensors.
     */
    fun showSensorFailureSilent(context: Context, failedSensors: List<Pair<String, Int>>) {
        val body = buildFailureBody(failedSensors)
        val manager = context.getSystemService(NotificationManager::class.java)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context, SENSOR_NA_NOTIFICATION_ID, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RainfallApp.SILENT_CHANNEL_ID)
            .setContentTitle("⚠️ Sensor Failure (Acknowledged)")
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDefaults(0)
            .build()

        manager.notify(SENSOR_NA_NOTIFICATION_ID, notification)
    }

    fun dismissSensorFailureAlert(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(SENSOR_NA_NOTIFICATION_ID)
    }

    // ── Shared helpers ─────────────────────────────────────────────────

    private fun getIntensityLabel(rate: Float): String = when {
        rate <= 0f -> "No Rain"
        rate <= 2.5f -> "Light Rain"
        rate <= 7.5f -> "Moderate Rain"
        rate <= 50f -> "Heavy Rain"
        else -> "Violent Rain"
    }
}
