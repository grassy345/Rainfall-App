package com.sreer.rainfallmonitor.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sreer.rainfallmonitor.AppConstants
import com.sreer.rainfallmonitor.R
import com.sreer.rainfallmonitor.data.AppDatabase
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import com.sreer.rainfallmonitor.repository.ConnectionState
import com.sreer.rainfallmonitor.repository.RainfallRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    // ── Sensor mode ────────────────────────────────────────────────
    private val _sensorMode = MutableStateFlow(SensorMode.RAINFALL)
    val sensorMode: StateFlow<SensorMode> = _sensorMode.asStateFlow()

    fun setSensorMode(mode: SensorMode) {
        _sensorMode.value = mode
    }

    // ── Live data from Repository (written by MqttService) ───────────
    val hourlyRate: StateFlow<Float> = RainfallRepository.hourlyRate
    val connectionState: StateFlow<ConnectionState> = RainfallRepository.connectionState
    val lastUpdated: StateFlow<Long> = RainfallRepository.lastUpdated
    val stateStartedAt: StateFlow<Long> = RainfallRepository.stateStartedAt

    // ── T/P/H live data from Repository ──────────────────────────────
    val lastValidPressure: StateFlow<Float?> = RainfallRepository.lastValidPressure
    val lastValidTemperature: StateFlow<Float?> = RainfallRepository.lastValidTemperature
    val lastValidHumidity: StateFlow<Float?> = RainfallRepository.lastValidHumidity
    val lastPressureUpdate: StateFlow<Long> = RainfallRepository.lastPressureUpdate
    val lastTemperatureUpdate: StateFlow<Long> = RainfallRepository.lastTemperatureUpdate
    val lastHumidityUpdate: StateFlow<Long> = RainfallRepository.lastHumidityUpdate

    // ── Today date tracking (auto-advances at midnight) ──────────────

    private val _todayDate = MutableStateFlow(getTodayDate())

    init {
        // Every 30 seconds, re-evaluate "today" to handle midnight rollover.
        // If the day changed, flatMapLatest auto-restarts the Room queries.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _todayDate.value = getTodayDate()
            }
        }
    }

    // ── Today's readings for graph preview ────────────────────────────

    /** Reactive Flow of all readings for today, re-queries at day change. */
    val todayReadings: Flow<List<RainfallReading>> =
        _todayDate.flatMapLatest { date ->
            db.rainfallReadingDao().getReadingsForDate(date)
        }

    /** Reactive Flow of the latest 10 readings for today, re-queries at day change. */
    val latestTodayReadings: Flow<List<RainfallReading>> =
        combine(_todayDate, RainfallRepository.lastUpdated) { date, _ -> date }
            .flatMapLatest { date ->
                db.rainfallReadingDao().getLatestReadingsForDate(date, 10)
            }

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

    // ═══════════════════════════════════════════════════════════════════
    // TEMPERATURE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    fun getTemperatureIntensityLabel(temp: Float): String = when {
        temp <= 15f -> "Cool"
        temp <= 25f -> "Mild"
        temp <= 35f -> "Warm"
        temp <= 40f -> "Hot"
        else -> "Extreme"
    }

    fun getTemperatureIntensityColorRes(temp: Float): Int = when {
        temp <= 15f -> R.color.temp_cool
        temp <= 25f -> R.color.temp_mild
        temp <= 35f -> R.color.temp_warm
        temp <= 40f -> R.color.temp_hot
        else -> R.color.temp_extreme
    }

    fun formatTemperature(temp: Float): String =
        String.format(Locale.US, "%.1f°C", temp)

    // ═══════════════════════════════════════════════════════════════════
    // PRESSURE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    fun getPressureIntensityLabel(pressure: Float): String = when {
        pressure < 1000f -> "Low"
        pressure < 1013f -> "Below Normal"
        pressure < 1025f -> "Normal"
        pressure < 1040f -> "High"
        else -> "Very High"
    }

    fun getPressureIntensityColorRes(pressure: Float): Int = when {
        pressure < 1000f -> R.color.pressure_low
        pressure < 1013f -> R.color.pressure_below_normal
        pressure < 1025f -> R.color.pressure_normal
        pressure < 1040f -> R.color.pressure_high
        else -> R.color.pressure_very_high
    }

    fun formatPressure(pressure: Float): String =
        String.format(Locale.US, "%.1f hPa", pressure)

    // ═══════════════════════════════════════════════════════════════════
    // HUMIDITY HELPERS
    // ═══════════════════════════════════════════════════════════════════

    fun getHumidityIntensityLabel(humidity: Float): String = when {
        humidity <= 30f -> "Dry"
        humidity <= 50f -> "Comfortable"
        humidity <= 70f -> "Humid"
        humidity <= 85f -> "Very Humid"
        else -> "Extreme"
    }

    fun getHumidityIntensityColorRes(humidity: Float): Int = when {
        humidity <= 30f -> R.color.humidity_dry
        humidity <= 50f -> R.color.humidity_comfortable
        humidity <= 70f -> R.color.humidity_humid
        humidity <= 85f -> R.color.humidity_very_humid
        else -> R.color.humidity_extreme
    }

    fun formatHumidity(humidity: Float): String =
        String.format(Locale.US, "%.0f%%", humidity)

    companion object {
        /** Staleness threshold — sourced from [AppConstants]. */
        private val STALE_THRESHOLD_MS = AppConstants.STALE_THRESHOLD_MS
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

enum class SensorMode {
    RAINFALL,
    TEMPERATURE,
    PRESSURE,
    HUMIDITY
}
