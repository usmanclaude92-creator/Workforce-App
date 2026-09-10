package com.example.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttendanceEvent(entity: PendingAttendanceEventEntity)

    @Update
    suspend fun updateAttendanceEvent(entity: PendingAttendanceEventEntity)

    @Query("SELECT * FROM pending_attendance_events WHERE employeeId = :employeeId AND syncStatus != 'SYNCED' ORDER BY queuedAtEpochMs ASC")
    suspend fun getUnsyncedAttendanceEvents(employeeId: String): List<PendingAttendanceEventEntity>

    @Query("SELECT * FROM pending_attendance_events WHERE syncStatus != 'SYNCED' ORDER BY queuedAtEpochMs ASC")
    suspend fun getAllUnsyncedAttendanceEvents(): List<PendingAttendanceEventEntity>

    @Query("SELECT * FROM pending_attendance_events WHERE employeeId = :employeeId ORDER BY queuedAtEpochMs DESC")
    fun observeAttendanceEvents(employeeId: String): Flow<List<PendingAttendanceEventEntity>>

    @Query("DELETE FROM pending_attendance_events WHERE syncStatus = 'SYNCED' AND syncedAtEpochMs < :beforeEpochMs")
    suspend fun pruneSyncedAttendanceEvents(beforeEpochMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLeaveRequest(entity: PendingLeaveRequestEntity)

    @Update
    suspend fun updateLeaveRequest(entity: PendingLeaveRequestEntity)

    @Query("SELECT * FROM pending_leave_requests WHERE employeeId = :employeeId AND syncStatus != 'SYNCED' ORDER BY queuedAtEpochMs ASC")
    suspend fun getUnsyncedLeaveRequests(employeeId: String): List<PendingLeaveRequestEntity>

    @Query("SELECT * FROM pending_leave_requests WHERE syncStatus != 'SYNCED' ORDER BY queuedAtEpochMs ASC")
    suspend fun getAllUnsyncedLeaveRequests(): List<PendingLeaveRequestEntity>

    @Query("SELECT * FROM pending_leave_requests WHERE employeeId = :employeeId ORDER BY queuedAtEpochMs DESC")
    fun observeLeaveRequests(employeeId: String): Flow<List<PendingLeaveRequestEntity>>

    @Query("DELETE FROM pending_leave_requests WHERE syncStatus = 'SYNCED' AND syncedAtEpochMs < :beforeEpochMs")
    suspend fun pruneSyncedLeaveRequests(beforeEpochMs: Long)
}
