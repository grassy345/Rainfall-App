package com.sreer.rainfallmonitor.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single reading from the ESP32 rainfall sensor.
 *
 * Every 10 minutes the ESP32 publishes a reading — rain or no rain.
 * Each message becomes one row in this table.
 *
 * - rate > 0  → rain is happening
 * - rate == 0 → no rain right now
 *
 * "Intensity" (Light Rain, Heavy Rain, etc.) is NOT stored here.
 * It's derived on-the-fly from [hourlyRate] whenever the UI needs it.
 */
@Entity(
    tableName = "rainfall_readings",
    indices = [Index(value = ["datestamp"])]
)
data class RainfallReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Raw hourly rate in mm/hr from ESP32. 0.0 means no rain. */
    val hourlyRate: Float,

    /** ESP32's NTP timestamp in milliseconds — source of truth, NOT the phone clock. */
    val timestamp: Long,

    /**
     * Date string in DD/MM/YYYY format, derived from [timestamp].
     * Indexed for fast date-based queries (graph, date picker).
     */
    val datestamp: String
)
