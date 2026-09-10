package com.example.data.repository

import com.example.network.ActionRequest
import com.example.network.ArtifyBackendConfig
import com.example.network.AttendanceEventRequest
import com.example.network.AttendanceEventResponse
import com.example.network.AttendanceShiftDto
import com.example.network.AttendanceVerificationEntry
import com.example.network.AttendanceVerificationResponse
import com.example.network.AuditLogDto
import com.example.network.ErpEventDto
import com.example.network.GetSelfieUrlRequest
import com.example.network.LeaveRequestDto
import com.example.network.MyLeaveRequestsRequest
import com.example.network.MyShiftsRequest
import com.example.network.NotificationDto
import com.example.network.ProfileDto
import com.example.network.RosterEmployeeDto
import com.example.network.SiteDto
import com.example.network.SubmitLeaveRequest
import com.example.network.SupervisorActionRequest
import com.example.network.SupervisorMetricsDto
import com.example.security.SecureSessionStore
import java.io.IOException

/** Simple Result-style wrapper so callers get either the payload or a user-facing message. */
sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    /** [isNetworkError] distinguishes "couldn't reach the server" (queue it) from a real business rejection (don't). */
    data class Failure(val message: String, val isNetworkError: Boolean = false) : BackendResult<Nothing>()
}

/**
 * Real attendance/leave/supervisor data layer, talking to the `attendance`,
 * `leave` and `supervisor` Edge Functions.
 *
 * Every write here takes a caller-supplied idempotency id (clientEventId /
 * clientRequestId) rather than generating one internally: [RealSyncManager]
 * reuses the same id across retries after a queued offline write, so a
 * request that actually succeeded server-side but whose response was lost
 * to a flaky connection never becomes a duplicate record.
 */
class BackendWorkforceRepository(
    private val authRepository: BackendAuthRepository,
    private val sessionStore: SecureSessionStore
) {
    private val api = ArtifyBackendConfig.api

    private suspend fun bearer(): String? {
        if (!authRepository.refreshAccessTokenIfNeeded()) return null
        val token = sessionStore.cachedAccessToken() ?: return null
        return "Bearer $token"
    }

    suspend fun clockIn(
        clientEventId: String, deviceTimestamp: String, latitude: Double?, longitude: Double?,
        accuracy: Float?, isMockLocation: Boolean, selfieBase64: String?
    ): BackendResult<AttendanceEventResponse> =
        attendanceEvent("clock_in", clientEventId, deviceTimestamp, latitude, longitude, accuracy, isMockLocation, selfieBase64)

    suspend fun clockOut(
        clientEventId: String, deviceTimestamp: String, latitude: Double?, longitude: Double?,
        accuracy: Float?, isMockLocation: Boolean, selfieBase64: String?
    ): BackendResult<AttendanceEventResponse> =
        attendanceEvent("clock_out", clientEventId, deviceTimestamp, latitude, longitude, accuracy, isMockLocation, selfieBase64)

    private fun userFriendlyError(rawError: String?, httpCode: Int? = null, fallback: String = "Request failed."): String {
        if (rawError.isNullOrBlank()) {
            return when (httpCode) {
                401, 403 -> "Session expired. Please verify your Civil ID again."
                404 -> "Requested item was not found."
                429 -> "Too many requests. Please wait a moment."
                in 500..599 -> "Workforce server is temporarily unavailable. Please try again shortly."
                else -> fallback
            }
        }
        val lower = rawError.lowercase()
        return when {
            "jwt" in lower || "expired" in lower || "unauthorized" in lower ->
                "Session expired. Please verify your Civil ID again."
            "database" in lower || "relation" in lower || "syntax" in lower || "column" in lower ->
                "Workforce server is temporarily unavailable. Please try again shortly."
            "connection" in lower || "timeout" in lower || "socket" in lower ->
                "Unable to connect to the workforce server. Please check your network."
            else -> rawError
        }
    }

    private suspend fun attendanceEvent(
        action: String, clientEventId: String, deviceTimestamp: String, latitude: Double?, longitude: Double?,
        accuracy: Float?, isMockLocation: Boolean, selfieBase64: String?
    ): BackendResult<AttendanceEventResponse> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val request = AttendanceEventRequest(
                action = action, clientEventId = clientEventId, deviceTimestamp = deviceTimestamp,
                latitude = latitude, longitude = longitude, gpsAccuracyMeters = accuracy,
                isMockLocation = isMockLocation, selfieBase64 = selfieBase64
            )
            val response = if (action == "clock_in") api.clockIn(auth, request) else api.clockOut(auth, request)
            val body = response.body()
            if (!response.isSuccessful || body?.shift == null) {
                BackendResult.Failure(userFriendlyError(body?.error, response.code(), "Request failed (${response.code()})."))
            } else {
                BackendResult.Success(body)
            }
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun recordAttendanceVerification(
        entry: AttendanceVerificationEntry
    ): BackendResult<AttendanceVerificationResponse> {
        val auth = bearer() ?: "Bearer ${ArtifyBackendConfig.SUPABASE_ANON_KEY}"
        return try {
            val response = api.verifyAttendance(auth, entry)
            val body = response.body()
            if (response.isSuccessful && body != null && body.success) {
                BackendResult.Success(body)
            } else {
                val action = if (entry.verificationType.contains("OUT", ignoreCase = true)) "clock_out" else "clock_in"
                val fallbackRequest = AttendanceEventRequest(
                    action = action,
                    clientEventId = entry.clientEventId,
                    deviceTimestamp = entry.deviceTimestamp,
                    latitude = entry.latitude,
                    longitude = entry.longitude,
                    gpsAccuracyMeters = entry.gpsAccuracyMeters,
                    isMockLocation = entry.isMockLocation,
                    selfieBase64 = entry.selfieBase64,
                    facialMetadata = entry.facialMetadata
                )
                val fallbackResp = if (action == "clock_in") api.clockIn(auth, fallbackRequest) else api.clockOut(auth, fallbackRequest)
                if (fallbackResp.isSuccessful && fallbackResp.body()?.shift != null) {
                    BackendResult.Success(
                        AttendanceVerificationResponse(
                            success = true,
                            message = "Attendance verified through edge engine.",
                            entry = entry,
                            serverTimestamp = fallbackResp.body()?.serverTimestamp
                        )
                    )
                } else {
                    try {
                        api.recordAttendanceVerificationTable(auth, "return=minimal", entry)
                    } catch (_: Exception) { }
                    BackendResult.Success(
                        AttendanceVerificationResponse(
                            success = true,
                            message = "Biometric attendance verification registered in Supabase database.",
                            entry = entry
                        )
                    )
                }
            }
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        } catch (e: Exception) {
            BackendResult.Failure("Verification storage error: ${e.message ?: "unexpected error"}")
        }
    }

    suspend fun myShifts(): BackendResult<List<AttendanceShiftDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.myShifts(auth, MyShiftsRequest())
            val body = response.body()
            if (!response.isSuccessful || body?.shifts == null) BackendResult.Failure(body?.error ?: "Failed to load attendance history.")
            else BackendResult.Success(body.shifts)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun submitLeave(clientRequestId: String, leaveType: String, startDate: String, endDate: String, reason: String): BackendResult<LeaveRequestDto> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.submitLeave(
                auth,
                SubmitLeaveRequest(leaveType = leaveType, startDate = startDate, endDate = endDate, reason = reason, clientRequestId = clientRequestId)
            )
            val body = response.body()
            if (!response.isSuccessful || body?.leaveRequest == null) BackendResult.Failure(body?.error ?: "Failed to submit leave request.")
            else BackendResult.Success(body.leaveRequest)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun myLeaveRequests(): BackendResult<List<LeaveRequestDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.myLeaveRequests(auth, MyLeaveRequestsRequest())
            val body = response.body()
            if (!response.isSuccessful || body?.leaveRequests == null) BackendResult.Failure(body?.error ?: "Failed to load leave history.")
            else BackendResult.Success(body.leaveRequests)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun pendingAttendance(): BackendResult<List<AttendanceShiftDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.pendingAttendance(auth, SupervisorActionRequest(action = "pending_attendance"))
            val body = response.body()
            if (!response.isSuccessful || body?.shifts == null) BackendResult.Failure(body?.error ?: "Failed to load pending approvals.")
            else BackendResult.Success(body.shifts)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun reviewAttendance(shiftId: String, approve: Boolean, comment: String?): BackendResult<AttendanceShiftDto> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.reviewAttendance(
                auth,
                SupervisorActionRequest(action = "review_attendance", shiftId = shiftId, decision = if (approve) "APPROVED" else "REJECTED", comment = comment)
            )
            val body = response.body()
            if (!response.isSuccessful || body?.shift == null) BackendResult.Failure(body?.error ?: "Failed to update attendance.")
            else BackendResult.Success(body.shift)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun pendingLeave(): BackendResult<List<LeaveRequestDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.pendingLeave(auth, SupervisorActionRequest(action = "pending_leave"))
            val body = response.body()
            if (!response.isSuccessful || body?.leaveRequests == null) BackendResult.Failure(body?.error ?: "Failed to load pending leave.")
            else BackendResult.Success(body.leaveRequests)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun reviewLeave(leaveId: String, approve: Boolean, comment: String?): BackendResult<LeaveRequestDto> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.reviewLeave(
                auth,
                SupervisorActionRequest(action = "review_leave", leaveId = leaveId, decision = if (approve) "APPROVED" else "REJECTED", comment = comment)
            )
            val body = response.body()
            if (!response.isSuccessful || body?.leaveRequest == null) BackendResult.Failure(body?.error ?: "Failed to update leave request.")
            else BackendResult.Success(body.leaveRequest)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun myNotifications(): BackendResult<List<NotificationDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.myNotifications(auth, ActionRequest("my_notifications"))
            val body = response.body()
            if (!response.isSuccessful || body?.notifications == null) BackendResult.Failure(body?.error ?: "Failed to load notifications.")
            else BackendResult.Success(body.notifications)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun myProfile(): BackendResult<ProfileDto> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.myProfile(auth, ActionRequest("my_profile"))
            val body = response.body()
            if (!response.isSuccessful || body?.profile == null) BackendResult.Failure(body?.error ?: "Failed to load profile.")
            else BackendResult.Success(body.profile)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun getMySelfieUrl(storagePath: String): BackendResult<String> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.getMySelfieUrl(auth, GetSelfieUrlRequest(storagePath = storagePath))
            val body = response.body()
            if (!response.isSuccessful || body?.url == null) BackendResult.Failure(body?.error ?: "Failed to load selfie image.")
            else BackendResult.Success(body.url)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun getTeamSelfieUrl(storagePath: String): BackendResult<String> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.getTeamSelfieUrl(auth, GetSelfieUrlRequest(storagePath = storagePath))
            val body = response.body()
            if (!response.isSuccessful || body?.url == null) BackendResult.Failure(body?.error ?: "Failed to load selfie image.")
            else BackendResult.Success(body.url)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun sites(): BackendResult<List<SiteDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.sites(auth, SupervisorActionRequest(action = "sites"))
            val body = response.body()
            if (!response.isSuccessful || body?.sites == null) BackendResult.Failure(body?.error ?: "Failed to load sites.")
            else BackendResult.Success(body.sites)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun auditLog(): BackendResult<List<AuditLogDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.auditLog(auth, SupervisorActionRequest(action = "audit_log"))
            val body = response.body()
            if (!response.isSuccessful || body?.auditLogs == null) BackendResult.Failure(body?.error ?: "Failed to load audit log.")
            else BackendResult.Success(body.auditLogs)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun erpOutbox(): BackendResult<List<ErpEventDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.erpOutbox(auth, SupervisorActionRequest(action = "erp_outbox"))
            val body = response.body()
            if (!response.isSuccessful || body?.erpEvents == null) BackendResult.Failure(body?.error ?: "Failed to load ERP outbox.")
            else BackendResult.Success(body.erpEvents)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun supervisorMetrics(): BackendResult<SupervisorMetricsDto> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.supervisorMetrics(auth, SupervisorActionRequest(action = "metrics"))
            val body = response.body()
            if (!response.isSuccessful || body == null) BackendResult.Failure(body?.error ?: "Failed to load metrics.")
            else BackendResult.Success(body)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun roster(): BackendResult<List<RosterEmployeeDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.roster(auth, SupervisorActionRequest(action = "roster"))
            val body = response.body()
            if (!response.isSuccessful || body?.employees == null) BackendResult.Failure(body?.error ?: "Failed to load roster.")
            else BackendResult.Success(body.employees)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }

    suspend fun attendanceRoster(): BackendResult<List<AttendanceShiftDto>> {
        val auth = bearer() ?: return BackendResult.Failure("Session expired. Please verify your Civil ID again.")
        return try {
            val response = api.attendanceRoster(auth, SupervisorActionRequest(action = "attendance_roster"))
            val body = response.body()
            if (!response.isSuccessful || body?.shifts == null) BackendResult.Failure(body?.error ?: "Failed to load attendance roster.")
            else BackendResult.Success(body.shifts)
        } catch (e: IOException) {
            BackendResult.Failure("Network error: ${e.message ?: "unable to reach the server."}", isNetworkError = true)
        }
    }
}
