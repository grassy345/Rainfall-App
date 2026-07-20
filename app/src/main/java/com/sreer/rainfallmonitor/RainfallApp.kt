package com.sreer.rainfallmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application class — entry point for app-wide initialization.
 *
 * Creates notification channels on startup. Android requires channels to be
 * created BEFORE any notification is posted to them. Creating them here
 * (in Application.onCreate) guarantees they exist before the service starts.
 *
 * Three channels:
 *   - mqtt_channel:  LOW importance — persistent "service running" notification
 *   - alert_channel: HIGH importance — rain alerts with sound + vibration
 *   - sensor_failure_silent: LOW importance — acknowledged sensor failure notifications
 */
class RainfallApp : Application() {

    companion object {
        const val MQTT_CHANNEL_ID = "mqtt_channel"
        const val ALERT_CHANNEL_ID = "alert_channel"
        const val SILENT_CHANNEL_ID = "sensor_failure_silent"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Persistent service notification — LOW importance (no sound, no popup)
        val mqttChannel = NotificationChannel(
            MQTT_CHANNEL_ID,
            "MQTT Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification showing MQTT connection status"
            setShowBadge(false)
        }

        // Rain alert notification — HIGH importance (sound + heads-up popup)
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Rain Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when rainfall is detected"
            enableVibration(true)
        }

        // Sensor failure (acknowledged) — LOW importance (silent, no popup)
        val silentChannel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "Sensor Failure (Acknowledged)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent notifications for acknowledged sensor failures"
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(mqttChannel, alertChannel, silentChannel))
    }
}
