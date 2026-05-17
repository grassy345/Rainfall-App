package com.sreer.rainfallmonitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.sreer.rainfallmonitor.data.entity.RainfallReading
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [RainfallReading].
 *
 * All queries return Flow for reactive UI updates, except insert/delete
 * which are suspend functions for coroutine usage.
 */
@Dao
interface RainfallReadingDao {

    /** Insert a new reading. Called every time the MQTT service receives a message. */
    @Insert
    suspend fun insert(reading: RainfallReading)

    /**
     * Get all readings for a specific date, ordered by time.
     * Used by the graph to plot the full day's data (including rate=0 points).
     */
    @Query("SELECT * FROM rainfall_readings WHERE datestamp = :date ORDER BY timestamp ASC")
    fun getReadingsForDate(date: String): Flow<List<RainfallReading>>

    /**
     * Get all distinct dates that have data.
     * Used by the date picker to only allow navigation to dates with readings.
     */
    @Query("SELECT DISTINCT datestamp FROM rainfall_readings ORDER BY timestamp DESC")
    fun getDatesWithData(): Flow<List<String>>

    /**
     * Get the most recent reading.
     * Used by the dashboard for current status and ESP32 aliveness check.
     */
    @Query("SELECT * FROM rainfall_readings ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReading(): Flow<RainfallReading?>

    /**
     * Delete readings older than the given timestamp.
     * Optional retention cleanup — call periodically if storage is a concern.
     */
    @Query("DELETE FROM rainfall_readings WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
