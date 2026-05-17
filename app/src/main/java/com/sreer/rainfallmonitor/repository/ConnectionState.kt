package com.sreer.rainfallmonitor.repository

/**
 * Represents the current MQTT connection state.
 *
 * The MQTT service updates this, and the dashboard UI observes it
 * to show a colored indicator (green/amber/red).
 */
enum class ConnectionState {
    /** Connected to HiveMQ and actively receiving data. */
    CONNECTED,

    /** Network is available but still trying to establish MQTT connection. */
    CONNECTING,

    /** No network or MQTT connection is down. */
    OFFLINE
}
