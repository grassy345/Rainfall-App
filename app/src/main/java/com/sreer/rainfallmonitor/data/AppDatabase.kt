package com.sreer.rainfallmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { INSTANCE = it }
            }
        }

        /**
         * Migration from v1 to v2: add unique index on timestamp.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_rainfall_readings_timestamp ON rainfall_readings(timestamp)")
            }
        }

        /**
         * Migration from v2 to v3: add pressure, temperature, humidity columns.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rainfall_readings ADD COLUMN pressure REAL")
                database.execSQL("ALTER TABLE rainfall_readings ADD COLUMN temperature REAL")
                database.execSQL("ALTER TABLE rainfall_readings ADD COLUMN humidity REAL")
            }
        }
    }
}
