package com.example.data.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Mirrors the states the master spec requires: PENDING, SYNCING, SYNCED, FAILED. */
object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
}

/**
 * A queued clock-in/out captured while offline (or while an online attempt
 * failed for a genuine network reason). [clientEventId] is the same id sent
 * to the server on every retry, so a request that actually landed but whose
 * response was lost never becomes a duplicate attendance record.
 */
@Entity(
    tableName = "pending_attendance_events",
    indices = [
        Index(value = ["employeeId", "syncStatus"]),
        Index(value = ["queuedAtEpochMs"])
    ]
)
data class PendingAttendanceEventEntity(
    @PrimaryKey val clientEventId: String,
    val employeeId: String,
    val action: String, // "clock_in" | "clock_out"
    val deviceTimestamp: String,
    val latitude: Double?,
    val longitude: Double?,
    val gpsAccuracyMeters: Float?,
    val isMockLocation: Boolean,
    val selfieLocalPath: String?,
    val queuedAtEpochMs: Long,
    val syncStatus: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextRetryAtEpochMs: Long = 0L,
    val syncedAtEpochMs: Long? = null
)

/** A queued leave submission; [clientRequestId] gives it the same idempotent-retry guarantee. */
@Entity(
    tableName = "pending_leave_requests",
    indices = [
        Index(value = ["employeeId", "syncStatus"]),
        Index(value = ["queuedAtEpochMs"])
    ]
)
data class PendingLeaveRequestEntity(
    @PrimaryKey val clientRequestId: String,
    val employeeId: String,
    val leaveType: String,
    val startDate: String,
    val endDate: String,
    val reason: String,
    val queuedAtEpochMs: Long,
    val syncStatus: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextRetryAtEpochMs: Long = 0L,
    val syncedAtEpochMs: Long? = null
)
