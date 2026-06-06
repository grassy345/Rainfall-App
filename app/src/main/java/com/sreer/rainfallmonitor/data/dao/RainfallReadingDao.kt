package com.sreer.rainfallmonitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /**
     * Insert a new reading. Called every time the MQTT service receives a message.
     * Uses IGNORE strategy: if a reading with the same timestamp already exists
     * (unique index), the duplicate is silently skipped. This handles QoS 1
     * redelivery without crashing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
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
     * Get the most recent reading as a reactive Flow.
     * Used by the dashboard for ongoing observation.
     */
    @Query("SELECT * FROM rainfall_readings ORDER BY timestamp DESC LIMIT 1")
    fun getLatestReading(): Flow<RainfallReading?>

    /**
     * Get the most recent reading as a one-shot query.
     * Used by MqttService to bootstrap the Repository on startup —
     * populates the dashboard with the last known state before any
     * MQTT messages arrive.
     */
    @Query("SELECT * FROM rainfall_readings ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestReadingOnce(): RainfallReading?

    /**
     * Get the most recent reading for a specific date.
     * Used for midnight boundary gap detection — check if the previous day's
     * last reading connects seamlessly to the current day's first reading.
     */
    @Query("SELECT * FROM rainfall_readings WHERE datestamp = :date ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastReadingForDate(date: String): RainfallReading?

    /**
     * Get the earliest reading for a specific date.
     * Used for midnight boundary gap detection — check if the current day's
     * last reading connects seamlessly to the next day's first reading.
     */
    @Query("SELECT * FROM rainfall_readings WHERE datestamp = :date ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstReadingForDate(date: String): RainfallReading?

    /**
     * Get the latest N readings for a specific date, newest-first.
     * Used by the dashboard's readings preview card (limit=10) and the
     * full-screen readings table (limit=-1 for all).
     */
    @Query("SELECT * FROM rainfall_readings WHERE datestamp = :date ORDER BY timestamp DESC LIMIT :limit")
    fun getLatestReadingsForDate(date: String, limit: Int): Flow<List<RainfallReading>>

    /**
     * Delete readings older than the given timestamp.
     * Optional retention cleanup — call periodically if storage is a concern.
     */
    @Query("DELETE FROM rainfall_readings WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
