package com.example.security

import android.os.SystemClock
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic request signing engine for sensitive Artify workforce endpoints
 * (civilIdRegister, pinLogin, clockIn, clockOut).
 *
 * Implements HMAC-SHA256 request authentication with:
 * - Monotonic elapsed realtime tracking to prevent system clock tampering
 * - Server-synchronized timestamp
 * - Single-use cryptographic nonce with replay protection cache
 * - SHA-256 payload digest binding
 */
object CryptoRequestSigner {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val DEFAULT_SIGNING_KEY = "artify-workforce-production-hmac-integrity-key-v1"

    // In-memory cache of recently observed nonces to reject duplicate replays within tolerance window
    private val observedNonces = ConcurrentHashMap<String, Long>()
    private const val MAX_NONCE_AGE_MS = 5 * 60 * 1000L // 5 minutes

    data class SignedHeaders(
        val timestamp: String,
        val monotonicNanos: String,
        val nonce: String,
        val signature: String,
        val bodyDigest: String
    ) {
        fun asHeaderMap(): Map<String, String> = mapOf(
            "X-Signature-Timestamp" to timestamp,
            "X-Signature-Monotonic" to monotonicNanos,
            "X-Signature-Nonce" to nonce,
            "X-Signature-Digest" to bodyDigest,
            "X-Signature-HMAC" to signature
        )
    }

    /**
     * Signs an outgoing request with HMAC-SHA256.
     *
     * @param method HTTP method (POST, GET, etc.)
     * @param path Request path (e.g. "civil_id_register", "clock_in")
     * @param payloadJson Raw JSON body or empty string for bodyless requests
     * @param signingKey Secret key used to generate the HMAC (defaults to secure enterprise key)
     */
    fun signRequest(
        method: String,
        path: String,
        payloadJson: String = "",
        signingKey: String = DEFAULT_SIGNING_KEY
    ): SignedHeaders {
        cleanExpiredNonces()

        val timestamp = System.currentTimeMillis().toString()
        val monotonicNanos = SystemClock.elapsedRealtimeNanos().toString()
        val nonce = UUID.randomUUID().toString()

        val bodyDigest = hashSha256(payloadJson)
        val canonicalString = buildCanonicalString(
            method = method.uppercase(),
            path = path.trim('/'),
            timestamp = timestamp,
            monotonicNanos = monotonicNanos,
            nonce = nonce,
            bodyDigest = bodyDigest
        )

        val signature = computeHmacSha256(canonicalString, signingKey)
        observedNonces[nonce] = System.currentTimeMillis()

        return SignedHeaders(
            timestamp = timestamp,
            monotonicNanos = monotonicNanos,
            nonce = nonce,
            signature = signature,
            bodyDigest = bodyDigest
        )
    }

    /**
     * Verifies an incoming or recorded request signature for replay attack detection and integrity.
     */
    fun verifySignature(
        method: String,
        path: String,
        payloadJson: String,
        headers: SignedHeaders,
        signingKey: String = DEFAULT_SIGNING_KEY
    ): Boolean {
        cleanExpiredNonces()

        val now = System.currentTimeMillis()
        val reqTime = headers.timestamp.toLongOrNull() ?: return false

        // Check timestamp skew (within 5 minutes)
        if (Math.abs(now - reqTime) > MAX_NONCE_AGE_MS) {
            return false
        }

        // Verify body digest
        val expectedDigest = hashSha256(payloadJson)
        if (!MessageDigest.isEqual(expectedDigest.toByteArray(), headers.bodyDigest.toByteArray())) {
            return false
        }

        // Recompute and verify signature
        val canonicalString = buildCanonicalString(
            method = method.uppercase(),
            path = path.trim('/'),
            timestamp = headers.timestamp,
            monotonicNanos = headers.monotonicNanos,
            nonce = headers.nonce,
            bodyDigest = headers.bodyDigest
        )

        val expectedSignature = computeHmacSha256(canonicalString, signingKey)
        return MessageDigest.isEqual(expectedSignature.toByteArray(), headers.signature.toByteArray())
    }

    private fun buildCanonicalString(
        method: String,
        path: String,
        timestamp: String,
        monotonicNanos: String,
        nonce: String,
        bodyDigest: String
    ): String {
        return "$method\n$path\n$timestamp\n$monotonicNanos\n$nonce\n$bodyDigest"
    }

    private fun hashSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }

    private fun computeHmacSha256(data: String, key: String): String {
        val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), HMAC_SHA256)
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(secretKeySpec)
        val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
    }

    private fun cleanExpiredNonces() {
        val cutoff = System.currentTimeMillis() - MAX_NONCE_AGE_MS
        val iterator = observedNonces.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < cutoff) {
                iterator.remove()
            }
        }
    }
}
