package com.sreer.rainfallmonitor

/**
 * Shared app-wide constants.
 *
 * Centralizing these avoids silent drift between copies in different files.
 */
object AppConstants {

    /**
     * Data staleness threshold (15 minutes).
     * Messages older than this are considered stale.
     * 15 min = 1.5× the ESP32's 10-minute heartbeat interval.
     * Also used as the gap detection threshold for "Since X ago".
     */
    const val STALE_THRESHOLD_MS = 15 * 60 * 1000L
}
