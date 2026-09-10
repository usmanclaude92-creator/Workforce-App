package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.network.BackendEmployee
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Device-bound session storage backed by Android Keystore (EncryptedSharedPreferences).
 * Deliberately excluded from Auto Backup / device transfer (see backup_rules.xml and
 * data_extraction_rules.xml) so a session can never silently reappear on a different
 * physical device — a new device must always go through Civil ID verification again.
 */
class SecureSessionStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun saveSession(
        employeeId: String,
        refreshToken: String,
        accessToken: String,
        accessTokenExpiresAtEpochSeconds: Long,
        employeeFullName: String,
        employeeCode: String,
        role: String,
        assignedProjectId: String?,
        isDemo: Boolean
    ) {
        prefs.edit()
            .putString(KEY_EMPLOYEE_ID, employeeId)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_TOKEN_EXP, accessTokenExpiresAtEpochSeconds)
            .putString(KEY_EMPLOYEE_NAME, employeeFullName)
            .putString(KEY_EMPLOYEE_CODE, employeeCode)
            .putString(KEY_ROLE, role)
            .putString(KEY_ASSIGNED_PROJECT_ID, assignedProjectId)
            .putBoolean(KEY_IS_DEMO, isDemo)
            .apply()
    }

    fun updateAccessToken(accessToken: String, expiresAtEpochSeconds: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_TOKEN_EXP, expiresAtEpochSeconds)
            .apply()
    }

    fun hasCachedSession(): Boolean =
        prefs.contains(KEY_EMPLOYEE_ID) && (prefs.contains(KEY_REFRESH_TOKEN) || prefs.contains(KEY_PIN_HASH))

    fun cachedEmployeeId(): String? = prefs.getString(KEY_EMPLOYEE_ID, null)
    fun cachedEmployeeName(): String? = prefs.getString(KEY_EMPLOYEE_NAME, null)
    fun cachedEmployeeCode(): String? = prefs.getString(KEY_EMPLOYEE_CODE, null)
    fun cachedRole(): String? = prefs.getString(KEY_ROLE, null)
    fun cachedAssignedProjectId(): String? = prefs.getString(KEY_ASSIGNED_PROJECT_ID, null)
    fun cachedRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)
    fun cachedAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)
    fun cachedAccessTokenExpiry(): Long = prefs.getLong(KEY_ACCESS_TOKEN_EXP, 0L)
    fun cachedIsDemo(): Boolean = prefs.getBoolean(KEY_IS_DEMO, false)

    /**
     * Reconstructs the cached BackendEmployee representation from encrypted local storage.
     */
    fun cachedEmployee(): BackendEmployee? {
        val id = cachedEmployeeId() ?: return null
        return BackendEmployee(
            id = id,
            employeeCode = cachedEmployeeCode() ?: "EMP-001",
            fullName = cachedEmployeeName() ?: "Employee",
            role = cachedRole() ?: "WORKER",
            assignedProjectId = cachedAssignedProjectId(),
            isDemo = cachedIsDemo()
        )
    }

    /**
     * Securely stores the 4-digit registration PIN using PBKDF2 with HmacSHA256 and a random 16-byte salt.
     * Persisted inside hardware-backed EncryptedSharedPreferences so the same PIN persists across app/device restarts.
     */
    fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashBase64 = Base64.encodeToString(hash, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_PIN_SALT, saltBase64)
            .putString(KEY_PIN_HASH, hashBase64)
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKED_UNTIL, 0L)
            .apply()
    }

    /**
     * Constant-time verification of the input PIN against the hardware-backed stored salt and hash.
     */
    fun verifyPin(pin: String): Boolean {
        val saltBase64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHashBase64 = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return try {
            val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
            val storedHash = Base64.decode(storedHashBase64, Base64.NO_WRAP)
            val computedHash = hashPin(pin, salt)
            MessageDigest.isEqual(storedHash, computedHash)
        } catch (e: Exception) {
            false
        }
    }

    fun hasRegisteredPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun getFailedAttempts(): Int = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0)

    fun recordFailedAttempt(): Int {
        val current = getFailedAttempts() + 1
        prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, current).apply()
        return current
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_LOCKED_UNTIL, 0L)
            .apply()
    }

    fun isLocked(): Boolean = System.currentTimeMillis() < prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0L)

    fun getLockoutRemainingSeconds(): Int {
        val diffMs = prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0L) - System.currentTimeMillis()
        return if (diffMs > 0) (diffMs / 1000).toInt() + 1 else 0
    }

    fun lockForSeconds(seconds: Int) {
        val lockUntil = System.currentTimeMillis() + (seconds * 1000L)
        prefs.edit().putLong(KEY_PIN_LOCKED_UNTIL, lockUntil).apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** Clears the session (logout) but keeps the device_id so re-registration reuses it. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_EMPLOYEE_ID)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_TOKEN_EXP)
            .remove(KEY_EMPLOYEE_NAME)
            .remove(KEY_EMPLOYEE_CODE)
            .remove(KEY_ROLE)
            .remove(KEY_ASSIGNED_PROJECT_ID)
            .remove(KEY_IS_DEMO)
            .apply()
    }

    /** Fully resets PIN registration state when switching user or explicit wipe is required. */
    fun clearPinRegistration() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_FAILED_ATTEMPTS)
            .remove(KEY_PIN_LOCKED_UNTIL)
            .apply()
    }

    /** Securely stored Supabase Anonymous Key */
    fun getSupabaseAnonKey(): String {
        return prefs.getString(KEY_SUPABASE_ANON_KEY, null)
            ?: com.example.network.ArtifyBackendConfig.SUPABASE_ANON_KEY
    }

    fun setSupabaseAnonKey(key: String) {
        prefs.edit().putString(KEY_SUPABASE_ANON_KEY, key).apply()
    }

    companion object {
        private const val FILE_NAME = "artify_secure_session"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_EMPLOYEE_ID = "employee_id"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_ACCESS_TOKEN_EXP = "access_token_exp"
        private const val KEY_EMPLOYEE_NAME = "employee_name"
        private const val KEY_EMPLOYEE_CODE = "employee_code"
        private const val KEY_ROLE = "role"
        private const val KEY_ASSIGNED_PROJECT_ID = "assigned_project_id"
        private const val KEY_IS_DEMO = "is_demo"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_FAILED_ATTEMPTS = "pin_failed_attempts"
        private const val KEY_PIN_LOCKED_UNTIL = "pin_locked_until"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"

        @Volatile private var instance: SecureSessionStore? = null
        fun getInstance(context: Context): SecureSessionStore =
            instance ?: synchronized(this) {
                instance ?: SecureSessionStore(context.applicationContext).also { instance = it }
            }
    }
}
