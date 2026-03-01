package com.swaran.airbridge.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.swaran.airbridge.core.data.db.dao.UploadQueueDao
import com.swaran.airbridge.core.data.db.entity.UploadQueueEntity

/**
 * Room database for AirBridge persistent storage.
 *
 * Contains:
 * - Upload queue table (survives app restarts)
 *
 * Database migrations should be added here when schema changes.
 */
@Database(
    entities = [UploadQueueEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AirBridgeDatabase : RoomDatabase() {

    abstract fun uploadQueueDao(): UploadQueueDao

    companion object {
        private const val DATABASE_NAME = "airbridge.db"

        @Volatile
        private var INSTANCE: AirBridgeDatabase? = null

        /**
         * Get singleton database instance.
         * Thread-safe with double-checked locking.
         */
        fun getInstance(context: Context): AirBridgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AirBridgeDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AirBridgeDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration() // For development; use migrations in production
                .build()
        }
    }
}