package com.example.data.dao

import androidx.room.*
import com.example.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE employeeId = :employeeId LIMIT 1")
    suspend fun getUserByEmployeeId(employeeId: String): UserEntity?

    @Query("SELECT * FROM users ORDER BY role ASC, fullName ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE role = :role ORDER BY fullName ASC")
    fun getUsersByRole(role: String): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET avatarUrl = :avatarUrl WHERE employeeId = :employeeId")
    suspend fun updateAvatarUrl(employeeId: String, avatarUrl: String)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun getUserCount(): Int
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY projectName ASC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProjects(projects: List<ProjectEntity>)

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun getProjectCount(): Int
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance ORDER BY createdAtUtc DESC")
    fun getAllAttendance(): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId ORDER BY createdAtUtc DESC")
    fun getAttendanceForEmployee(employeeId: String): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE attendanceId = :id LIMIT 1")
    suspend fun getAttendanceById(id: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId AND shiftDate = :shiftDate AND endTimeUtc IS NULL AND state != 'CANCELLED' LIMIT 1")
    suspend fun getActiveShift(employeeId: String, shiftDate: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId AND endTimeUtc IS NULL AND state != 'CANCELLED' LIMIT 1")
    suspend fun getAnyActiveShift(employeeId: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE state = 'PENDING_APPROVAL' ORDER BY createdAtUtc DESC")
    fun getPendingApprovals(): Flow<List<AttendanceEntity>>

    @Query("SELECT * FROM attendance WHERE shiftDate = :date ORDER BY createdAtUtc DESC")
    fun getAttendanceForDate(date: String): Flow<List<AttendanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(attendance: AttendanceEntity)

    @Update
    suspend fun updateAttendance(attendance: AttendanceEntity)

    @Query("SELECT * FROM attendance WHERE syncedToFirestore = 0 ORDER BY createdAtUtc ASC")
    suspend fun getUnsyncedAttendance(): List<AttendanceEntity>

    @Query("SELECT * FROM attendance WHERE syncedToFirestore = 0 ORDER BY createdAtUtc ASC")
    fun getUnsyncedAttendanceFlow(): Flow<List<AttendanceEntity>>

    @Query("SELECT COUNT(*) FROM attendance WHERE syncedToFirestore = 0")
    fun getUnsyncedAttendanceCount(): Flow<Int>

    @Query("UPDATE attendance SET syncedToFirestore = 1, firestoreSyncStatus = 'SYNCED', firestoreSyncedAtUtc = :syncedAtUtc WHERE attendanceId = :id")
    suspend fun markAttendanceSyncedToFirestore(id: String, syncedAtUtc: Long)

    @Query("UPDATE attendance SET firestoreSyncStatus = :status WHERE attendanceId = :id")
    suspend fun updateFirestoreSyncStatus(id: String, status: String)

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId ORDER BY createdAtUtc DESC LIMIT 1")
    suspend fun getLatestAttendanceForWorker(employeeId: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId ORDER BY createdAtUtc DESC LIMIT :limit")
    fun getRecentShiftsForEmployee(employeeId: String, limit: Int = 20): Flow<List<AttendanceEntity>>

    @Query("SELECT COUNT(*) FROM attendance")
    suspend fun getAttendanceCount(): Int
}

@Dao
interface LeaveDao {
    @Query("SELECT * FROM leave_requests ORDER BY submittedAtUtc DESC")
    fun getAllLeaveRequests(): Flow<List<LeaveRequestEntity>>

    @Query("SELECT * FROM leave_requests WHERE employeeId = :employeeId ORDER BY submittedAtUtc DESC")
    fun getLeaveRequestsForEmployee(employeeId: String): Flow<List<LeaveRequestEntity>>

    @Query("SELECT * FROM leave_requests WHERE requestId = :id LIMIT 1")
    suspend fun getLeaveRequestById(id: String): LeaveRequestEntity?

    @Query("SELECT * FROM leave_requests WHERE status = 'PENDING' ORDER BY submittedAtUtc DESC")
    fun getPendingLeaveRequests(): Flow<List<LeaveRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeaveRequest(request: LeaveRequestEntity)

    @Update
    suspend fun updateLeaveRequest(request: LeaveRequestEntity)

    @Query("SELECT COUNT(*) FROM leave_requests")
    suspend fun getLeaveCount(): Int
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit_logs ORDER BY serverTimestampUtc DESC")
    fun getAllAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE recipientId = :employeeId OR recipientId = 'ALL' OR recipientId = 'ALL_SUPERVISORS' ORDER BY timestampUtc DESC")
    fun getNotificationsForUser(employeeId: String): Flow<List<NotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Query("UPDATE notifications SET isRead = 1 WHERE notificationId = :id")
    suspend fun markAsRead(id: String)
}

@Dao
interface ErpDao {
    @Query("SELECT * FROM erp_outbox ORDER BY createdAtUtc DESC")
    fun getAllOutboxEvents(): Flow<List<ErpOutboxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxEvent(event: ErpOutboxEntity)

    @Update
    suspend fun updateOutboxEvent(event: ErpOutboxEntity)
}
