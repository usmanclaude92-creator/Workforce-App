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

/** Action types a supervisor can perform against a team member's attendance/leave while offline. */
object SupervisorActionType {
    const val PROXY_CLOCK_IN = "PROXY_CLOCK_IN"
    const val PROXY_CLOCK_OUT = "PROXY_CLOCK_OUT"
    const val ATTENDANCE_APPROVAL = "ATTENDANCE_APPROVAL"
    const val LEAVE_REVIEW = "LEAVE_REVIEW"
}

/**
 * A queued supervisor action (proxy clock-in/out for a team member, attendance approval/
 * rejection, leave approval/rejection) captured while offline. [payloadJson] holds the
 * action-specific fields (see [SupervisorActionType]) so one table/queue covers all four,
 * the same idempotent-retry guarantee as the worker's own queues via [clientActionId].
 */
@Entity(
    tableName = "pending_supervisor_actions",
    indices = [
        Index(value = ["supervisorId", "syncStatus"]),
        Index(value = ["queuedAtEpochMs"])
    ]
)
data class PendingSupervisorActionEntity(
    @PrimaryKey val clientActionId: String,
    val supervisorId: String,
    val actionType: String,
    val payloadJson: String,
    val selfieLocalPath: String? = null,
    val queuedAtEpochMs: Long,
    val syncStatus: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextRetryAtEpochMs: Long = 0L,
    val syncedAtEpochMs: Long? = null
)
