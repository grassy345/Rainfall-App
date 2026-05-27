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

    // ── Write methods (called by MQTT service) ───────────────────────

    /**
     * Called by MqttService when a new reading arrives from ESP32.
     *
     * @param rate     The hourly rate in mm/hr (0.0 = no rain)
     * @param timestamp The ESP32's NTP timestamp in milliseconds
     * @param hasDataGap True if the gap between this reading and the previous
     *                   one is larger than the expected interval (15 min).
     *                   Forces a reset of [stateStartedAt] because we can't
     *                   claim continuity across a gap — it might have rained
     *                   (or stopped raining) during the gap.
     */
    fun updateReading(rate: Float, timestamp: Long, hasDataGap: Boolean = false) {
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
    }

    /**
     * Called by MqttService to update the connection state.
     * The dashboard shows green/amber/red based on this.
     */
    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
