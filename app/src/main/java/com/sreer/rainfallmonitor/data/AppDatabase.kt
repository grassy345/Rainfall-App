package com.sreer.rainfallmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sreer.rainfallmonitor.data.dao.RainfallReadingDao
import com.sreer.rainfallmonitor.data.entity.RainfallReading

/**
 * Room database for the Rainfall Monitor app.
 *
 * Single table: [RainfallReading] — every ESP32 message is one row.
 *
 * Access via [AppDatabase.getInstance] which provides a thread-safe singleton.
 */
@Database(
    entities = [RainfallReading::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rainfallReadingDao(): RainfallReadingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Returns the singleton database instance, creating it on first call.
         *
         * Thread-safe via double-checked locking.
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rainfall_monitor.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
