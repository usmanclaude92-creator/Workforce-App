package com.example.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** Maps 1:1 onto the deployed `artify-workforce` Supabase Edge Functions. */
interface SupabaseApi {

    @POST("civil-id-register")
    suspend fun civilIdRegister(@Body request: CivilIdRegisterRequest): Response<CivilIdRegisterResponse>

    @POST("pin-login")
    suspend fun pinLogin(@Body request: PinLoginRequest): Response<PinLoginResponse>

    @POST("refresh-session")
    suspend fun refreshSession(@Body request: RefreshSessionRequest): Response<RefreshSessionResponse>

    @POST("logout")
    suspend fun logout(@Header("Authorization") bearerToken: String): Response<LogoutResponse>

    @POST("attendance")
    suspend fun clockIn(@Header("Authorization") bearerToken: String, @Body request: AttendanceEventRequest): Response<AttendanceEventResponse>

    @POST("attendance")
    suspend fun clockOut(@Header("Authorization") bearerToken: String, @Body request: AttendanceEventRequest): Response<AttendanceEventResponse>

    @POST("attendance-verify")
    suspend fun verifyAttendance(@Header("Authorization") bearerToken: String, @Body request: AttendanceVerificationEntry): Response<AttendanceVerificationResponse>

    @POST("${ArtifyBackendConfig.SUPABASE_URL}/rest/v1/attendance_verifications")
    suspend fun recordAttendanceVerificationTable(
        @Header("Authorization") bearerToken: String,
        @Header("Prefer") prefer: String = "return=minimal",
        @Body request: AttendanceVerificationEntry
    ): Response<Unit>

    @POST("attendance")
    suspend fun myShifts(@Header("Authorization") bearerToken: String, @Body request: MyShiftsRequest): Response<MyShiftsResponse>

    @POST("leave")
    suspend fun submitLeave(@Header("Authorization") bearerToken: String, @Body request: SubmitLeaveRequest): Response<SubmitLeaveResponse>

    @POST("leave")
    suspend fun myLeaveRequests(@Header("Authorization") bearerToken: String, @Body request: MyLeaveRequestsRequest): Response<MyLeaveRequestsResponse>

    @POST("supervisor")
    suspend fun pendingAttendance(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<PendingAttendanceResponse>

    @POST("supervisor")
    suspend fun reviewAttendance(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<ReviewAttendanceResponse>

    @POST("supervisor")
    suspend fun pendingLeave(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<PendingLeaveResponse>

    @POST("supervisor")
    suspend fun reviewLeave(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<ReviewLeaveResponse>

    @POST("supervisor")
    suspend fun roster(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<RosterResponse>

    @POST("supervisor")
    suspend fun attendanceRoster(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<PendingAttendanceResponse>

    @POST("attendance")
    suspend fun myNotifications(@Header("Authorization") bearerToken: String, @Body request: ActionRequest): Response<MyNotificationsResponse>

    @POST("attendance")
    suspend fun myProfile(@Header("Authorization") bearerToken: String, @Body request: ActionRequest): Response<MyProfileResponse>

    @POST("attendance")
    suspend fun getMySelfieUrl(@Header("Authorization") bearerToken: String, @Body request: GetSelfieUrlRequest): Response<GetSelfieUrlResponse>

    @POST("supervisor")
    suspend fun getTeamSelfieUrl(@Header("Authorization") bearerToken: String, @Body request: GetSelfieUrlRequest): Response<GetSelfieUrlResponse>

    @POST("supervisor")
    suspend fun sites(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<SitesResponse>

    @POST("supervisor")
    suspend fun auditLog(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<AuditLogResponse>

    @POST("supervisor")
    suspend fun erpOutbox(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<ErpOutboxResponse>

    @POST("supervisor")
    suspend fun supervisorMetrics(@Header("Authorization") bearerToken: String, @Body request: SupervisorActionRequest): Response<SupervisorMetricsDto>
}
