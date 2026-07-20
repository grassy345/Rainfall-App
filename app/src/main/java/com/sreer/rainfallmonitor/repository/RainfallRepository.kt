package com.sreer.rainfallmonitor.repository

import android.util.Log
import com.sreer.rainfallmonitor.AppConstants
import com.sreer.rainfallmonitor.ui.SensorMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central "bulletin board" for live rainfall data.
 *
 * The MQTT service WRITES to this when it receives readings from the ESP32.
 * The UI ViewModels READ from this to display the current status.
 *
 * Uses StateFlow — a reactive holder that automatically notifies all
 * observers (UI screens) whenever a value changes. No manual refreshing.
 *
 * This is a Kotlin singleton (object) so the MQTT service and UI ViewModels
 * always access the exact same instance. Thread-safe by design — StateFlow
 * is safe to read/write from any thread.
 *
 * NOTE: This holds the CURRENT live state only. Historical data is in Room DB.
 */
object RainfallRepository {

    // ── Private mutable flows (only this class can write) ────────────

    private val _hourlyRate = MutableStateFlow(0f)
    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE)
    private val _lastUpdated = MutableStateFlow(0L)
    private val _stateStartedAt = MutableStateFlow(0L)
    private val _lastValidPressure = MutableStateFlow<Float?>(null)
    private val _lastValidTemperature = MutableStateFlow<Float?>(null)
    private val _lastValidHumidity = MutableStateFlow<Float?>(null)
    private val _lastPressureUpdate = MutableStateFlow(0L)
    private val _lastTemperatureUpdate = MutableStateFlow(0L)
    private val _lastHumidityUpdate = MutableStateFlow(0L)
    private val _sensorMode = MutableStateFlow(SensorMode.RAINFALL)
    private val _fullscreenSelectedDate = MutableStateFlow("")
    private val _isFullscreenChainActive = MutableStateFlow(false)

    // ── Fresh-message tracking for consecutive null detection ────────
    // These count how many consecutive FRESH messages have reported null
    // for each sensor. Stale messages (>15 min old) NEVER change these.
    // Reset to 0 on fresh-data gaps (>15 min since last fresh message).
    // Also reset to 0 when a non-null value arrives.

    /** Timestamp of the last FRESH message. Used to detect gaps in fresh
     *  data when stale messages may have bridged the gap in [_lastUpdated]. */
    private val _lastFreshTimestamp = MutableStateFlow(0L)

    private val _consecutiveFreshPressureNull = MutableStateFlow(0)
    private val _consecutiveFreshTemperatureNull = MutableStateFlow(0)
    private val _consecutiveFreshHumidityNull = MutableStateFlow(0)

    // ── Public read-only flows (UI ViewModels observe these) ─────────

    /** Current hourly rate in mm/hr. 0.0 = no rain. */
    val hourlyRate: StateFlow<Float> = _hourlyRate.asStateFlow()

    /** Current MQTT connection state (CONNECTED, CONNECTING, OFFLINE). */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Timestamp (millis) of the last received reading from ESP32. */
    val lastUpdated: StateFlow<Long> = _lastUpdated.asStateFlow()

    /**
     * Timestamp (millis) when the current rain/no-rain state began.
     *
     * Example: if it was "No Rain" and then became "Light Rain" at 2:30 PM,
     * stateStartedAt = the timestamp of 2:30 PM. The UI uses this to show
     * "Raining since 45 minutes ago".
     *
     * Reset whenever the state transitions:
     *   - No rain -> rain (any intensity)
     *   - Rain -> no rain
     */
    val stateStartedAt: StateFlow<Long> = _stateStartedAt.asStateFlow()

    /** Last non-null pressure in hPa. null if never received. */
    val lastValidPressure: StateFlow<Float?> = _lastValidPressure.asStateFlow()

    /** Last non-null temperature in Celsius. null if never received. */
    val lastValidTemperature: StateFlow<Float?> = _lastValidTemperature.asStateFlow()

    /** Last non-null humidity in %. null if never received. */
    val lastValidHumidity: StateFlow<Float?> = _lastValidHumidity.asStateFlow()

    /** Timestamp of the last non-null pressure reading. 0 if none. */
    val lastPressureUpdate: StateFlow<Long> = _lastPressureUpdate.asStateFlow()

    /** Timestamp of the last non-null temperature reading. 0 if none. */
    val lastTemperatureUpdate: StateFlow<Long> = _lastTemperatureUpdate.asStateFlow()

    /** Timestamp of the last non-null humidity reading. 0 if none. */
    val lastHumidityUpdate: StateFlow<Long> = _lastHumidityUpdate.asStateFlow()

    /** Currently selected sensor mode (RAINFALL / TEMPERATURE / PRESSURE / HUMIDITY). */
    val sensorMode: StateFlow<SensorMode> = _sensorMode.asStateFlow()

    /** Shared date between full-screen windows. Empty = not set. */
    val fullscreenSelectedDate: StateFlow<String> = _fullscreenSelectedDate.asStateFlow()

    /** True when user navigated from one full-screen window to another. */
    val isFullscreenChainActive: StateFlow<Boolean> = _isFullscreenChainActive.asStateFlow()

    /** Consecutive FRESH null count for pressure (0 = working or non-null). */
    val consecutiveFreshPressureNull: StateFlow<Int> = _consecutiveFreshPressureNull.asStateFlow()

    /** Consecutive FRESH null count for temperature (0 = working or non-null). */
    val consecutiveFreshTemperatureNull: StateFlow<Int> = _consecutiveFreshTemperatureNull.asStateFlow()

    /** Consecutive FRESH null count for humidity (0 = working or non-null). */
    val consecutiveFreshHumidityNull: StateFlow<Int> = _consecutiveFreshHumidityNull.asStateFlow()

    // ── Write methods (called by MQTT service) ───────────────────────

    /**
     * Result from [updateReading]. Contains:
     * - [accepted]: true if the message was newer than the last known timestamp
     * - [consecutivePressureNull]: consecutive FRESH null count for pressure after this message
     * - [consecutiveTemperatureNull]: same for temperature
     * - [consecutiveHumidityNull]: same for humidity
     */
    data class UpdateResult(
        val accepted: Boolean,
        val consecutivePressureNull: Int,
        val consecutiveTemperatureNull: Int,
        val consecutiveHumidityNull: Int
    )

    /**
     * Called by MqttService when a reading arrives from ESP32.
     *
     * Thread-safe via [@Synchronized]. Combined with the timestamp guard,
     * this prevents the bootstrap coroutine from overwriting a fresh MQTT
     * message that arrived moments earlier.
     *
     * The timestamp guard rejects any call whose timestamp is NOT strictly
     * newer than the current [lastUpdated]. This handles:
     *   1. Bootstrap race: bootstrap (t=1000) arrives after MQTT (t=2000)
     *      → rejected (1000 <= 2000)
     *   2. Duplicate messages: same timestamp re-delivered via QoS 1
     *      → rejected (idempotent)
     *   3. Genuine new data: always accepted (t > lastUpdated)
     *
     * @param rate     The hourly rate in mm/hr (0.0 = no rain)
     * @param timestamp The ESP32's NTP timestamp in milliseconds
     * @param hasDataGap True if the gap between this reading and the previous
     *                   one is larger than the expected interval (15 min).
     *                   Passed through to inform caller logic (e.g. rain alerts).
     *                   Note: no longer resets [stateStartedAt] — the UI
     *                   strikethrough instead warns of gaps visually.
     * @param isStale   True if the message is > 15 min old. Stale messages
     *                  update the dashboard display but do NOT affect the
     *                  consecutive-null tracking (to avoid burst corruption).
     */
    @Synchronized
    fun updateReading(
        rate: Float,
        timestamp: Long,
        hasDataGap: Boolean,
        isStale: Boolean = false,
        pressure: Float? = null,
        temperature: Float? = null,
        humidity: Float? = null
    ): UpdateResult {
        // Reject older or duplicate timestamps — prevents bootstrap race
        // and makes QoS 1 redelivery idempotent.
        if (_lastUpdated.value != 0L && timestamp <= _lastUpdated.value) {
            return UpdateResult(false, _consecutiveFreshPressureNull.value,
                _consecutiveFreshTemperatureNull.value, _consecutiveFreshHumidityNull.value)
        }

        val wasRaining = _hourlyRate.value > 0f
        val isRaining = rate > 0f
        val isFirstReading = _lastUpdated.value == 0L

        // Reset stateStartedAt on:
        //   1. First-ever reading (no prior state)
        //   2. State transition (rain ↔ no rain)
        if (isFirstReading || wasRaining != isRaining) {
            _stateStartedAt.value = timestamp
        }

        _hourlyRate.value = rate
        _lastUpdated.value = timestamp

        // Update per-sensor display values (dashboard reads these)
        _lastValidPressure.value = pressure
        _lastPressureUpdate.value = timestamp
        _lastValidTemperature.value = temperature
        _lastTemperatureUpdate.value = timestamp
        _lastValidHumidity.value = humidity
        _lastHumidityUpdate.value = timestamp

        // ── Fresh-message tracking ─────────────────────────────────
        // Stale messages do NOT touch tracking — they only update display.
        if (!isStale) {
            // Detect gaps in FRESH messages using _lastFreshTimestamp.
            // Stale messages update _lastUpdated but NOT _lastFreshTimestamp,
            // so this correctly detects offline periods even when stale
            // messages bridged the gap in _lastUpdated.
            val freshGap = _lastFreshTimestamp.value != 0L &&
                    (timestamp - _lastFreshTimestamp.value) > AppConstants.STALE_THRESHOLD_MS

            // Reset tracking on gap — old nulls from before a gap don't count.
            if (freshGap) {
                _consecutiveFreshPressureNull.value = 0
                _consecutiveFreshTemperatureNull.value = 0
                _consecutiveFreshHumidityNull.value = 0
            }

            // Increment on null, reset to 0 on non-null
            // If there was a fresh gap, the counts were already reset above,
            // and the current message's value is the new baseline.
            _consecutiveFreshPressureNull.value = if (pressure == null)
                _consecutiveFreshPressureNull.value + 1 else 0
            _consecutiveFreshTemperatureNull.value = if (temperature == null)
                _consecutiveFreshTemperatureNull.value + 1 else 0
            _consecutiveFreshHumidityNull.value = if (humidity == null)
                _consecutiveFreshHumidityNull.value + 1 else 0

            _lastFreshTimestamp.value = timestamp

            return UpdateResult(true,
                _consecutiveFreshPressureNull.value,
                _consecutiveFreshTemperatureNull.value,
                _consecutiveFreshHumidityNull.value)
        }

        // Stale messages don't touch tracking — return current counts for logging
        return UpdateResult(true,
            _consecutiveFreshPressureNull.value,
            _consecutiveFreshTemperatureNull.value,
            _consecutiveFreshHumidityNull.value)
    }

    /**
     * Called by MqttService on startup to restore consecutive null counts
     * from SharedPrefs (so tracking survives service restarts).
     *
     * @param pressureCount   Persisted consecutive-null count for pressure
     * @param temperatureCount  Same for temperature
     * @param humidityCount     Same for humidity
     */
    @Synchronized
    fun initConsecutiveNullCounts(pressureCount: Int, temperatureCount: Int, humidityCount: Int) {
        _consecutiveFreshPressureNull.value = pressureCount
        _consecutiveFreshTemperatureNull.value = temperatureCount
        _consecutiveFreshHumidityNull.value = humidityCount
        Log.d("RainfallRepository", "Initialised consecutive-null counts: P=$pressureCount, T=$temperatureCount, H=$humidityCount")
    }

    /**
     * Called by MqttService on startup to restore the last fresh timestamp
     * from SharedPrefs (so gap detection works across service restarts).
     */
    @Synchronized
    fun initLastFreshTimestamp(timestamp: Long) {
        _lastFreshTimestamp.value = timestamp
    }

    /**
     * Called by MqttService to update the connection state.
     * The dashboard shows green/amber/red based on this.
     */
    @Synchronized
    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Called by any UI component to switch the active sensor mode.
     * This single source of truth ensures all screens stay in sync.
     */
    @Synchronized
    fun setSensorMode(mode: SensorMode) {
        _sensorMode.value = mode
    }

    /**
     * Called by full-screen activities when the user navigates to a date.
     * The date is shared between Graph and Readings windows only.
     */
    @Synchronized
    fun setFullscreenSelectedDate(date: String) {
        _fullscreenSelectedDate.value = date
    }

    /**
     * Tracks whether we are in a chain of full-screen windows
     * (Graph ↔ Readings). Used to restore the shared date on resume.
     * Set true when switching between full-screen windows,
     * set false when launching from the main dashboard.
     */
    @Synchronized
    fun updateFullscreenChain(active: Boolean) {
        _isFullscreenChainActive.value = active
    }
}
