package com.example.data.repository

import com.example.network.AttendanceEventResponse
import com.example.network.AttendanceShiftDto
import com.example.network.AttendanceVerificationEntry
import com.example.network.AttendanceVerificationResponse
import com.example.network.AuditLogDto
import com.example.network.ErpEventDto
import com.example.network.LeaveRequestDto
import com.example.network.NotificationDto
import com.example.network.ProfileDto
import com.example.network.RosterEmployeeDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto

/**
 * Common abstraction for Workforce data operations.
 * Allows the exact same screens, viewmodels, and workflows to consume
 * either Real backend data (BackendWorkforceRepository) or isolated mock
 * data (DemoWorkforceRepository).
 */
interface IWorkforceRepository {
    suspend fun clockIn(
        clientEventId: String,
        deviceTimestamp: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieBase64: String?
    ): BackendResult<AttendanceEventResponse>

    suspend fun clockOut(
        clientEventId: String,
        deviceTimestamp: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieBase64: String?
    ): BackendResult<AttendanceEventResponse>

    suspend fun recordAttendanceVerification(
        entry: AttendanceVerificationEntry
    ): BackendResult<AttendanceVerificationResponse>

    suspend fun myShifts(): BackendResult<List<AttendanceShiftDto>>

    suspend fun submitLeave(
        clientRequestId: String,
        leaveType: String,
        startDate: String,
        endDate: String,
        reason: String
    ): BackendResult<LeaveRequestDto>

    suspend fun myLeaveRequests(): BackendResult<List<LeaveRequestDto>>

    suspend fun pendingAttendance(): BackendResult<List<AttendanceShiftDto>>

    suspend fun reviewAttendance(
        shiftId: String,
        approve: Boolean,
        comment: String?
    ): BackendResult<AttendanceShiftDto>

    suspend fun pendingLeave(): BackendResult<List<LeaveRequestDto>>

    suspend fun reviewLeave(
        leaveId: String,
        approve: Boolean,
        comment: String?
    ): BackendResult<LeaveRequestDto>

    suspend fun myNotifications(): BackendResult<List<NotificationDto>>

    suspend fun myProfile(): BackendResult<ProfileDto>

    suspend fun getMySelfieUrl(storagePath: String): BackendResult<String>

    suspend fun getTeamSelfieUrl(storagePath: String): BackendResult<String>

    suspend fun sites(): BackendResult<List<SiteDto>>

    suspend fun auditLog(): BackendResult<List<AuditLogDto>>

    suspend fun erpOutbox(): BackendResult<List<ErpEventDto>>

    suspend fun supervisorMetrics(): BackendResult<SupervisorMetricsDto>

    suspend fun roster(): BackendResult<List<RosterEmployeeDto>>

    suspend fun attendanceRoster(): BackendResult<List<AttendanceShiftDto>>
}
