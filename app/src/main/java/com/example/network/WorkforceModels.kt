package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmployeeSummary(
    @Json(name = "full_name") val fullName: String,
    @Json(name = "employee_code") val employeeCode: String? = null,
    val role: String? = null
)

@JsonClass(generateAdapter = true)
data class ProjectSummary(val name: String)

@JsonClass(generateAdapter = true)
data class AttendanceEventSummary(
    @Json(name = "server_timestamp") val serverTimestamp: String? = null,
    @Json(name = "geofence_status") val geofenceStatus: String? = null,
    @Json(name = "distance_from_project_meters") val distanceFromProjectMeters: Double? = null,
    @Json(name = "selfie_storage_path") val selfieStoragePath: String? = null,
    @Json(name = "is_mock_location") val isMockLocation: Boolean? = null,
    @Json(name = "device_id") val deviceId: String? = null
)

@JsonClass(generateAdapter = true)
data class AttendanceShiftDto(
    val id: String,
    @Json(name = "employee_id") val employeeId: String,
    @Json(name = "project_id") val projectId: String,
    @Json(name = "shift_date") val shiftDate: String,
    @Json(name = "clock_in_event_id") val clockInEventId: String,
    @Json(name = "clock_out_event_id") val clockOutEventId: String? = null,
    @Json(name = "total_worked_minutes") val totalWorkedMinutes: Int? = null,
    val status: String,
    @Json(name = "compliance_flag") val complianceFlag: String,
    @Json(name = "reviewed_by") val reviewedBy: String? = null,
    @Json(name = "reviewed_at") val reviewedAt: String? = null,
    @Json(name = "review_comment") val reviewComment: String? = null,
    @Json(name = "clock_in") val clockIn: AttendanceEventSummary? = null,
    @Json(name = "clock_out") val clockOut: AttendanceEventSummary? = null,
    val employee: EmployeeSummary? = null,
    val project: ProjectSummary? = null
)

@JsonClass(generateAdapter = true)
data class FacialMetadataDto(
    @Json(name = "face_detected") val faceDetected: Boolean = false,
    @Json(name = "face_count") val faceCount: Int = 0,
    @Json(name = "confidence") val confidence: Float = 0f,
    @Json(name = "eyes_distance") val eyesDistance: Float = 0f,
    @Json(name = "midpoint_x") val midpointX: Float = 0f,
    @Json(name = "midpoint_y") val midpointY: Float = 0f,
    @Json(name = "pose_tilt") val poseTilt: Float = 0f,
    @Json(name = "pose_turn") val poseTurn: Float = 0f,
    @Json(name = "pose_roll") val poseRoll: Float = 0f,
    @Json(name = "luminance") val luminance: Double = 0.0,
    @Json(name = "is_well_lit") val isWellLit: Boolean = true,
    @Json(name = "is_centered") val isCentered: Boolean = true,
    @Json(name = "sharpness_score") val sharpnessScore: Double = 0.0,
    @Json(name = "image_width") val imageWidth: Int = 0,
    @Json(name = "image_height") val imageHeight: Int = 0,
    @Json(name = "compliance_status") val complianceStatus: String = "VERIFIED",
    @Json(name = "compliance_reason") val complianceReason: String = "Face verified within standard parameters"
)

@JsonClass(generateAdapter = true)
data class AttendanceVerificationEntry(
    val id: String,
    @Json(name = "client_event_id") val clientEventId: String,
    @Json(name = "employee_id") val employeeId: String,
    @Json(name = "employee_name") val employeeName: String,
    @Json(name = "project_id") val projectId: String? = null,
    @Json(name = "project_name") val projectName: String? = null,
    @Json(name = "verification_type") val verificationType: String = "SELFIE_VERIFICATION",
    @Json(name = "device_timestamp") val deviceTimestamp: String,
    @Json(name = "selfie_url") val selfieUrl: String? = null,
    @Json(name = "selfie_base64") val selfieBase64: String? = null,
    @Json(name = "facial_metadata") val facialMetadata: FacialMetadataDto,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @Json(name = "gps_accuracy_meters") val gpsAccuracyMeters: Float? = null,
    @Json(name = "is_mock_location") val isMockLocation: Boolean = false,
    @Json(name = "verification_status") val verificationStatus: String = "VERIFIED",
    val notes: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class AttendanceVerificationResponse(
    val success: Boolean = true,
    val message: String? = null,
    val entry: AttendanceVerificationEntry? = null,
    @Json(name = "server_timestamp") val serverTimestamp: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class AttendanceEventRequest(
    val action: String,
    @Json(name = "client_event_id") val clientEventId: String,
    @Json(name = "device_timestamp") val deviceTimestamp: String,
    val latitude: Double?,
    val longitude: Double?,
    @Json(name = "gps_accuracy_meters") val gpsAccuracyMeters: Float?,
    @Json(name = "is_mock_location") val isMockLocation: Boolean,
    @Json(name = "selfie_base64") val selfieBase64: String?,
    @Json(name = "facial_metadata") val facialMetadata: FacialMetadataDto? = null
)

@JsonClass(generateAdapter = true)
data class AttendanceEventResponse(
    val shift: AttendanceShiftDto? = null,
    @Json(name = "server_timestamp") val serverTimestamp: String? = null,
    @Json(name = "geofence_status") val geofenceStatus: String? = null,
    @Json(name = "distance_meters") val distanceMeters: Double? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class MyShiftsRequest(val action: String = "my_shifts")

@JsonClass(generateAdapter = true)
data class MyShiftsResponse(val shifts: List<AttendanceShiftDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class LeaveRequestDto(
    val id: String,
    @Json(name = "employee_id") val employeeId: String,
    @Json(name = "leave_type") val leaveType: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "total_days") val totalDays: Double,
    val reason: String,
    val status: String,
    @Json(name = "decision_reason") val decisionReason: String? = null,
    val employee: EmployeeSummary? = null
)

@JsonClass(generateAdapter = true)
data class SubmitLeaveRequest(
    val action: String = "submit",
    @Json(name = "leave_type") val leaveType: String,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    val reason: String,
    @Json(name = "client_request_id") val clientRequestId: String
)

@JsonClass(generateAdapter = true)
data class SubmitLeaveResponse(@Json(name = "leave_request") val leaveRequest: LeaveRequestDto? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class MyLeaveRequestsRequest(val action: String = "my_requests")

@JsonClass(generateAdapter = true)
data class MyLeaveRequestsResponse(@Json(name = "leave_requests") val leaveRequests: List<LeaveRequestDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class SupervisorActionRequest(
    val action: String,
    @Json(name = "shift_id") val shiftId: String? = null,
    @Json(name = "leave_id") val leaveId: String? = null,
    val decision: String? = null,
    val comment: String? = null
)

@JsonClass(generateAdapter = true)
data class PendingAttendanceResponse(val shifts: List<AttendanceShiftDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class ReviewAttendanceResponse(val shift: AttendanceShiftDto? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class PendingLeaveResponse(@Json(name = "leave_requests") val leaveRequests: List<LeaveRequestDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class ReviewLeaveResponse(@Json(name = "leave_request") val leaveRequest: LeaveRequestDto? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class RosterEmployeeDto(
    val id: String,
    @Json(name = "employee_code") val employeeCode: String,
    @Json(name = "full_name") val fullName: String,
    val role: String,
    @Json(name = "employment_status") val employmentStatus: String,
    val project: ProjectSummary? = null
)

@JsonClass(generateAdapter = true)
data class RosterResponse(val employees: List<RosterEmployeeDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class ActionRequest(val action: String)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    val id: String, val type: String, val title: String, val message: String, val timestamp: String? = null
)

@JsonClass(generateAdapter = true)
data class MyNotificationsResponse(val notifications: List<NotificationDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class ProfileDto(
    @Json(name = "full_name") val fullName: String,
    @Json(name = "employee_code") val employeeCode: String,
    val role: String,
    val department: String? = null,
    val email: String? = null,
    val phone: String? = null,
    @Json(name = "company_name") val companyName: String? = null,
    @Json(name = "is_demo") val isDemo: Boolean,
    @Json(name = "project_name") val projectName: String? = null,
    @Json(name = "project_code") val projectCode: String? = null,
    @Json(name = "project_address") val projectAddress: String? = null,
    @Json(name = "project_latitude") val projectLatitude: Double? = null,
    @Json(name = "project_longitude") val projectLongitude: Double? = null,
    @Json(name = "geofence_radius_meters") val geofenceRadiusMeters: Double? = null
)

@JsonClass(generateAdapter = true)
data class MyProfileResponse(val profile: ProfileDto? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class GetSelfieUrlRequest(val action: String = "get_selfie_url", @Json(name = "storage_path") val storagePath: String)

@JsonClass(generateAdapter = true)
data class GetSelfieUrlResponse(val url: String? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class SiteDto(
    val id: String, @Json(name = "project_code") val projectCode: String, val name: String,
    val address: String? = null, @Json(name = "geofence_radius_meters") val geofenceRadiusMeters: Double,
    @Json(name = "is_active") val isActive: Boolean, val employees: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class SitesResponse(val sites: List<SiteDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class AuditLogDto(
    val id: String, @Json(name = "actor_employee_id") val actorEmployeeId: String? = null,
    @Json(name = "actor_role") val actorRole: String? = null, val action: String,
    @Json(name = "entity_type") val entityType: String, @Json(name = "entity_id") val entityId: String? = null,
    val reason: String? = null, @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class AuditLogResponse(@Json(name = "audit_logs") val auditLogs: List<AuditLogDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class ErpEventDto(
    val id: String, @Json(name = "event_type") val eventType: String, val status: String,
    @Json(name = "idempotency_key") val idempotencyKey: String, @Json(name = "response_ref") val responseRef: String? = null,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class ErpOutboxResponse(@Json(name = "erp_events") val erpEvents: List<ErpEventDto>? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class SupervisorMetricsDto(
    val present: Int = 0, val working: Int = 0,
    @Json(name = "attendance_pending") val attendancePending: Int = 0,
    @Json(name = "leave_pending") val leavePending: Int = 0,
    @Json(name = "on_leave") val onLeave: Int = 0,
    val error: String? = null
)
