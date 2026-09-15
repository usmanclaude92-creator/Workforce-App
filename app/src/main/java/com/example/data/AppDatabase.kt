package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.*
import com.example.data.entity.*

@Database(
    entities = [
        UserEntity::class,
        ProjectEntity::class,
        AttendanceEntity::class,
        LeaveRequestEntity::class,
        AuditLogEntity::class,
        NotificationEntity::class,
        ErpOutboxEntity::class,
        AttendanceRecordEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun projectDao(): ProjectDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun leaveDao(): LeaveDao
    abstract fun auditDao(): AuditDao
    abstract fun notificationDao(): NotificationDao
    abstract fun erpDao(): ErpDao
    abstract fun attendanceRecordDao(): AttendanceRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "artify_workforce.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
