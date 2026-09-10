package com.example.data.sync

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Offline pending-write queue for the REAL (backend-authenticated) flow only.
 * Deliberately separate from the Demo Mode `AppDatabase` — this holds writes
 * awaiting the Artify Central Backend, not a self-contained fake dataset.
 */
@Database(
    entities = [PendingAttendanceEventEntity::class, PendingLeaveRequestEntity::class, CachedJsonEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RealSyncDatabase : RoomDatabase() {
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun cacheDao(): CacheDao

    companion object {
        @Volatile private var instance: RealSyncDatabase? = null

        fun getInstance(context: Context): RealSyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, RealSyncDatabase::class.java, "artify_real_sync_v2.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
