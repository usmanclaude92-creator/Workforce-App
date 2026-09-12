package com.example.data.repository

import com.example.network.ArtifyBackendConfig
import com.example.network.BackendEmployee
import com.example.network.CivilIdRegisterRequest
import com.example.network.PinLoginRequest
import com.example.network.RefreshSessionRequest
import com.example.security.SecureSessionStore
import java.io.IOException

sealed class CivilIdRegisterOutcome {
    data class Success(val employee: BackendEmployee) : CivilIdRegisterOutcome()
    object NotEligible : CivilIdRegisterOutcome()
    data class Error(val message: String) : CivilIdRegisterOutcome()
}

sealed class PinLoginOutcome {
    data class Success(val employee: BackendEmployee) : PinLoginOutcome()
    object NeedsRegistration : PinLoginOutcome()
    data class Locked(val secondsRemaining: Int) : PinLoginOutcome()
    data class IncorrectPin(val attemptsRemaining: Int?) : PinLoginOutcome()
    data class Error(val message: String) : PinLoginOutcome()
}

/**
 * Talks to the real Artify Central Backend (Supabase Edge Functions) for
 * Civil ID verification and device-bound PIN authentication. Entirely
 * separate from WorkforceRepository, which remains the Demo Mode data source.
 */
class BackendAuthRepository(private val sessionStore: SecureSessionStore) {

    private val api = ArtifyBackendConfig.api

    val deviceId: String get() = sessionStore.deviceId

    suspend fun registerWithCivilId(civilId: String, pin: String): CivilIdRegisterOutcome {
        return try {
            val response = api.civilIdRegister(
                CivilIdRegisterRequest(civilId = civilId, deviceId = deviceId, pin = pin, companyCode = ArtifyBackendConfig.COMPANY_CODE)
            )
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return CivilIdRegisterOutcome.Error(body?.error ?: "Registration failed (${response.code()}).")
            }
            if (!body.eligible || body.accessToken == null || body.refreshToken == null || body.employee == null) {
                return CivilIdRegisterOutcome.NotEligible
            }
            persistSession(body.employee, body.accessToken, body.refreshToken)
            sessionStore.savePin(pin)
            CivilIdRegisterOutcome.Success(body.employee)
        } catch (e: IOException) {
            CivilIdRegisterOutcome.Error("Network error: ${e.message ?: "unable to reach the server."}")
        }
    }

    suspend fun loginWithPin(pin: String): PinLoginOutcome {
        val employeeId = sessionStore.cachedEmployeeId()
            ?: return PinLoginOutcome.NeedsRegistration

        // 1. Check local device lockout first
        if (sessionStore.isLocked()) {
            return PinLoginOutcome.Locked(sessionStore.getLockoutRemainingSeconds())
        }

        // 2. Hardware-backed secure PIN verification (guarantees PIN works across restarts/reboots)
        if (sessionStore.hasRegisteredPin()) {
            val isValid = sessionStore.verifyPin(pin)
            if (!isValid) {
                val attempts = sessionStore.recordFailedAttempt()
                val maxAttempts = 5
                return if (attempts >= maxAttempts) {
                    sessionStore.lockForSeconds(300)
                    PinLoginOutcome.Locked(300)
                } else {
                    PinLoginOutcome.IncorrectPin(maxAttempts - attempts)
                }
            }

            // PIN verified successfully! Reset failed counter
            sessionStore.resetFailedAttempts()

            val cachedEmployee = sessionStore.cachedEmployee()
                ?: return PinLoginOutcome.NeedsRegistration

            // Asynchronously ensure access token is fresh if online
            try {
                refreshAccessTokenIfNeeded()
            } catch (ignored: Exception) {
                // Offline login is fully supported with verified local PIN
            }

            return PinLoginOutcome.Success(cachedEmployee)
        }

        // 3. Fallback to remote API for initial verification if local PIN not yet stored
        return try {
            val response = api.pinLogin(PinLoginRequest(employeeId = employeeId, deviceId = deviceId, pin = pin))
            val body = response.body()
            when {
                body?.needsRegistration == true -> {
                    sessionStore.clearSession()
                    PinLoginOutcome.NeedsRegistration
                }
                response.code() == 423 -> PinLoginOutcome.Locked(body?.lockedForSeconds ?: 900)
                !response.isSuccessful || body?.accessToken == null || body.employee == null ->
                    PinLoginOutcome.IncorrectPin(body?.attemptsRemaining)
                else -> {
                    val refreshToken = sessionStore.cachedRefreshToken() ?: return PinLoginOutcome.NeedsRegistration
                    persistSession(body.employee, body.accessToken, refreshToken)
                    sessionStore.savePin(pin) // Save for future offline/reboot verification
                    PinLoginOutcome.Success(body.employee)
                }
            }
        } catch (e: IOException) {
            PinLoginOutcome.Error("Network error: ${e.message ?: "unable to reach the server."}")
        }
    }

    /** Refreshes the access token using the stored refresh token; call before it expires. */
    suspend fun refreshAccessTokenIfNeeded(): Boolean {
        val expiry = sessionStore.cachedAccessTokenExpiry()
        val nowSeconds = System.currentTimeMillis() / 1000
        if (expiry - nowSeconds > 60) return true // still valid for at least another minute

        val employeeId = sessionStore.cachedEmployeeId() ?: return false
        val refreshToken = sessionStore.cachedRefreshToken() ?: return false
        return try {
            val response = api.refreshSession(RefreshSessionRequest(employeeId, deviceId, refreshToken))
            val body = response.body()
            if (body?.needsRegistration == true) {
                sessionStore.clearSession()
                return false
            }
            val newToken = body?.accessToken ?: return false
            sessionStore.updateAccessToken(newToken, decodeExpiry(newToken))
            true
        } catch (e: IOException) {
            false
        }
    }

    suspend fun logout() {
        val token = sessionStore.cachedAccessToken()
        if (token != null) {
            try {
                api.logout("Bearer $token")
            } catch (e: IOException) {
                // Best-effort revoke; the token will simply expire (max 15 min) if this fails.
            }
        }
        sessionStore.clearSession()
    }

    fun hasCachedSession(): Boolean = sessionStore.hasCachedSession()
    fun cachedEmployeeName(): String? = sessionStore.cachedEmployeeName()

    private fun persistSession(employee: BackendEmployee, accessToken: String, refreshToken: String) {
        sessionStore.saveSession(
            employeeId = employee.id,
            refreshToken = refreshToken,
            accessToken = accessToken,
            accessTokenExpiresAtEpochSeconds = decodeExpiry(accessToken),
            employeeFullName = employee.fullName,
            employeeCode = employee.employeeCode,
            role = employee.role,
            assignedProjectId = employee.assignedProjectId,
            isDemo = employee.isDemo
        )
    }

    /**
     * The backend (`pin-login` / `civil-id-register` / `refresh-session` edge functions) issues an
     * opaque, non-JWT session token of the form `session_<employeeId>_<uuid>` — it has no header/payload/
     * signature segments and no embedded `exp` claim, and `device_sessions` carries no expiry column either;
     * the token stays valid until it is explicitly rotated by a refresh call. There is nothing to decode.
     *
     * This used to attempt a JWT-style decode here, which always failed for these tokens and fell back to
     * "expires in 60s" — so [refreshAccessTokenIfNeeded] treated every session as already expired and
     * forced an extra refresh-session round trip (and token rotation) before every single authenticated
     * request. Assume a long, safe validity window instead so a healthy session isn't refreshed needlessly;
     * a session actually invalidated server-side still surfaces normally as a "Session expired" failure.
     */
    private fun decodeExpiry(@Suppress("UNUSED_PARAMETER") token: String): Long {
        return (System.currentTimeMillis() / 1000) + ASSUMED_SESSION_VALIDITY_SECONDS
    }

    companion object {
        private const val ASSUMED_SESSION_VALIDITY_SECONDS = 12L * 60 * 60 // 12 hours
    }
}
