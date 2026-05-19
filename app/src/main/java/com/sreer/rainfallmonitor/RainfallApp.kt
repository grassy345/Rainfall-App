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
 * Two channels:
 *   - mqtt_channel:  LOW importance — persistent "service running" notification
 *   - alert_channel: HIGH importance — rain alerts with sound + vibration
 */
class RainfallApp : Application() {

    companion object {
        const val MQTT_CHANNEL_ID = "mqtt_channel"
        const val ALERT_CHANNEL_ID = "alert_channel"
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

        manager.createNotificationChannels(listOf(mqttChannel, alertChannel))
    }
}
