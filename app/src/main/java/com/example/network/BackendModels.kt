package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CivilIdRegisterRequest(
    @Json(name = "civil_id") val civilId: String,
    @Json(name = "device_id") val deviceId: String,
    val pin: String,
    @Json(name = "company_code") val companyCode: String? = null
)

@JsonClass(generateAdapter = true)
data class CivilIdRegisterResponse(
    val eligible: Boolean,
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    val employee: BackendEmployee? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class BackendEmployee(
    val id: String,
    @Json(name = "employee_code") val employeeCode: String,
    @Json(name = "full_name") val fullName: String,
    val role: String,
    @Json(name = "assigned_project_id") val assignedProjectId: String?,
    @Json(name = "is_demo") val isDemo: Boolean
)

@JsonClass(generateAdapter = true)
data class PinLoginRequest(
    @Json(name = "civil_id") val civilId: String,
    @Json(name = "device_id") val deviceId: String,
    val pin: String
)

@JsonClass(generateAdapter = true)
data class PinLoginResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    val employee: BackendEmployee? = null,
    val error: String? = null,
    @Json(name = "needs_registration") val needsRegistration: Boolean = false,
    @Json(name = "attempts_remaining") val attemptsRemaining: Int? = null,
    @Json(name = "locked_for_seconds") val lockedForSeconds: Int? = null
)

@JsonClass(generateAdapter = true)
data class RefreshSessionRequest(
    @Json(name = "employee_id") val employeeId: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "refresh_token") val refreshToken: String
)

@JsonClass(generateAdapter = true)
data class RefreshSessionResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    val error: String? = null,
    @Json(name = "needs_registration") val needsRegistration: Boolean = false
)

@JsonClass(generateAdapter = true)
data class LogoutResponse(
    val success: Boolean = false,
    val error: String? = null
)
