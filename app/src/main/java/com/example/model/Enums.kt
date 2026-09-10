package com.example.model

enum class UserRole(val displayName: String, val idPrefix: String) {
    WORKER("Worker", "ART-W-"),
    STAFF("Staff", "ART-S-"),
    SUPERVISOR("Supervisor", "ART-SUP-")
}

enum class AttendanceState(val displayName: String) {
    DRAFT("Draft"),
    SUBMITTED("Submitted"),
    PENDING_APPROVAL("Pending Approval"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
    FLAGGED("Flagged for Review"),
    CANCELLED("Cancelled")
}

enum class VerificationStatus(val displayName: String) {
    VERIFIED("Server Verified"),
    FLAGGED("Flagged Suspicious"),
    REJECTED("Rejected"),
    PENDING_REVIEW("Pending Review")
}

enum class GeofenceStatus(val displayName: String) {
    INSIDE_GEOFENCE("Inside Geofence"),
    OUTSIDE_GEOFENCE("Outside Geofence"),
    ACCURACY_TOO_LOW("GPS Accuracy Low"),
    GPS_UNAVAILABLE("GPS Unavailable"),
    MOCK_LOCATION_DETECTED("Mock Location Detected")
}

enum class LeaveType(val displayName: String, val code: String) {
    ABSENT("Absent", "ABS"),
    SICK_LEAVE("Sick Leave", "SL"),
    CASUAL_LEAVE("Casual Leave", "CL"),
    ANNUAL_LEAVE("Annual Leave", "AL"),
    TRANSIT("Transit", "TR")
}

enum class LeaveStatus(val displayName: String) {
    PENDING("Pending"),
    APPROVED("Approved"),
    REJECTED("Rejected")
}

enum class ShiftEventType {
    START_SHIFT,
    END_SHIFT
}

enum class AuditAction {
    USER_REGISTERED,
    LOGIN,
    SHIFT_STARTED,
    SHIFT_ENDED,
    ATTENDANCE_SUBMITTED,
    ATTENDANCE_APPROVED,
    ATTENDANCE_REJECTED,
    LEAVE_SUBMITTED,
    LEAVE_APPROVED,
    LEAVE_REJECTED,
    PROJECT_ASSIGNED,
    SHIFT_ASSIGNED,
    SCHEDULE_CHANGED,
    PROFILE_UPDATED
}
