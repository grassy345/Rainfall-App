package com.sreer.rainfallmonitor.repository

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

    // ── Write methods (called by MQTT service) ───────────────────────

    /**
     * Called by MqttService when a new reading arrives from ESP32.
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
     *                   Forces a reset of [stateStartedAt] because we can't
     *                   claim continuity across a gap — it might have rained
     *                   (or stopped raining) during the gap.
     */
    @Synchronized
    fun updateReading(
        rate: Float,
        timestamp: Long,
        hasDataGap: Boolean,
        pressure: Float? = null,
        temperature: Float? = null,
        humidity: Float? = null
    ): Boolean {
        // Reject older or duplicate timestamps — prevents bootstrap race
        // and makes QoS 1 redelivery idempotent.
        if (_lastUpdated.value != 0L && timestamp <= _lastUpdated.value) return false

        val wasRaining = _hourlyRate.value > 0f
        val isRaining = rate > 0f
        val isFirstReading = _lastUpdated.value == 0L

        // Reset stateStartedAt on:
        //   1. First-ever reading (no prior state)
        //   2. State transition (rain ↔ no rain)
        //   3. Data gap detected (can't claim continuity through gap)
        if (isFirstReading || wasRaining != isRaining || hasDataGap) {
            _stateStartedAt.value = timestamp
        }

        _hourlyRate.value = rate
        _lastUpdated.value = timestamp

        // Update per-sensor last-valid tracking (only if non-null)
        if (pressure != null) {
            _lastValidPressure.value = pressure
            _lastPressureUpdate.value = timestamp
        }
        if (temperature != null) {
            _lastValidTemperature.value = temperature
            _lastTemperatureUpdate.value = timestamp
        }
        if (humidity != null) {
            _lastValidHumidity.value = humidity
            _lastHumidityUpdate.value = timestamp
        }
        return true
    }

    /**
     * Called by MqttService to update the connection state.
     * The dashboard shows green/amber/red based on this.
     */
    @Synchronized
    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
