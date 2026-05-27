package com.sreer.rainfallmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sreer.rainfallmonitor.data.AppDatabase
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.repository.ConnectionState
import com.sreer.rainfallmonitor.repository.RainfallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for the main dashboard screen.
 *
 * Exposes:
 *   - Live rainfall data from [RainfallRepository] (StateFlows)
 *   - Today's readings from Room DB (Flow) for the graph preview
 *   - Helper methods to derive display strings (intensity, duration, etc.)
 *
 * Survives configuration changes (screen rotation). The Repository is a
 * singleton, so the ViewModel always reads the latest data.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    // ── Live data from Repository (written by MqttService) ───────────
    val hourlyRate: StateFlow<Float> = RainfallRepository.hourlyRate
    val connectionState: StateFlow<ConnectionState> = RainfallRepository.connectionState
    val lastUpdated: StateFlow<Long> = RainfallRepository.lastUpdated
    val stateStartedAt: StateFlow<Long> = RainfallRepository.stateStartedAt

    // ── Today's readings for graph preview ────────────────────────────

    /**
     * Returns a Flow of today's readings for the graph preview.
     *
     * This is a FUNCTION (not a property) because the date string must
     * be evaluated fresh each time. If it were a val, it would capture
     * the date at ViewModel creation time and break at midnight.
     */
    fun getTodayReadings(): Flow<List<RainfallReading>> =
        db.rainfallReadingDao().getReadingsForDate(getTodayDate())

    /** Returns today's date in dd/MM/yyyy format (matches MqttService's datestamp). */
    fun getTodayDate(): String = dateFormat.format(Date())

    // ═══════════════════════════════════════════════════════════════════
    // DISPLAY HELPERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Derives the rain intensity label from the hourly rate.
     *
     * Thresholds based on WMO (World Meteorological Organization):
     *   - 0.0          → No Rain
     *   - 0.1 – 2.5    → Light Rain
     *   - 2.5 – 7.5    → Moderate Rain
     *   - 7.5 – 50.0   → Heavy Rain
     *   - > 50.0       → Violent Rain
     */
    fun getIntensityLabel(rate: Float): String = when {
        rate <= 0f -> "No Rain"
        rate <= 2.5f -> "Light Rain"
        rate <= 7.5f -> "Moderate Rain"
        rate <= 50f -> "Heavy Rain"
        else -> "Violent Rain"
    }

    /**
     * Returns the color resource ID for the given intensity.
     */
    fun getIntensityColorRes(rate: Float): Int = when {
        rate <= 0f -> com.sreer.rainfallmonitor.R.color.intensity_none
        rate <= 2.5f -> com.sreer.rainfallmonitor.R.color.intensity_light
        rate <= 7.5f -> com.sreer.rainfallmonitor.R.color.intensity_moderate
        rate <= 50f -> com.sreer.rainfallmonitor.R.color.intensity_heavy
        else -> com.sreer.rainfallmonitor.R.color.intensity_violent
    }

    /**
     * Formats the hourly rate to 2 decimal places.
     * e.g., 2.4 → "2.40", 0.0 → "0.00"
     */
    fun formatRate(rate: Float): String = String.format(Locale.US, "%.2f", rate)

    /**
     * Formats a duration from a timestamp to "X min ago", "X hours ago", etc.
     * Returns "Waiting for data…" if timestamp is 0.
     */
    fun formatTimeAgo(timestamp: Long): String {
        if (timestamp == 0L) return "Waiting for data…"

        val now = System.currentTimeMillis()
        val diffMs = now - timestamp
        if (diffMs < 0) return "just now"

        val minutes = diffMs / 60_000
        val hours = minutes / 60

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            else -> {
                val days = hours / 24
                "$days day${if (days > 1) "s" else ""} ago"
            }
        }
    }

    /**
     * Threshold for considering data stale (15 minutes).
     * Matches MqttService.STALE_THRESHOLD_MS.
     * 15 min = 1.5× the ESP32's 10-min heartbeat interval.
     */
    companion object {
        private const val STALE_THRESHOLD_MS = 15 * 60 * 1000L
    }

    /**
     * Returns true if the last reading is older than 15 minutes.
     * Used to grey out the intensity text (data is stale but not necessarily
     * offline — could be ESP32 power cut, WiFi drop, etc.)
     */
    fun isDataStale(lastUpdated: Long): Boolean {
        if (lastUpdated == 0L) return false
        return System.currentTimeMillis() - lastUpdated > STALE_THRESHOLD_MS
    }

    /**
     * Returns true if the ESP32 appears offline:
     * - Phone is connected to HiveMQ (green indicator)
     * - BUT the last reading is older than 15 minutes
     *
     * This means the phone-to-server link is fine, but the
     * ESP32-to-server link may be broken.
     */
    fun isEsp32Offline(connectionState: ConnectionState, lastUpdated: Long): Boolean {
        if (connectionState != ConnectionState.CONNECTED) return false
        if (lastUpdated == 0L) return false
        return System.currentTimeMillis() - lastUpdated > STALE_THRESHOLD_MS
    }

    /**
     * Returns the color resource ID for the connection state dot.
     */
    fun getConnectionColorRes(state: ConnectionState): Int = when (state) {
        ConnectionState.CONNECTED -> com.sreer.rainfallmonitor.R.color.state_connected
        ConnectionState.CONNECTING -> com.sreer.rainfallmonitor.R.color.state_connecting
        ConnectionState.OFFLINE -> com.sreer.rainfallmonitor.R.color.state_offline
    }

    /**
     * Returns the display label for the connection state.
     */
    fun getConnectionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.OFFLINE -> "Offline"
    }
}
