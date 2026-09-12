package com.example.network

import com.example.BuildConfig
import com.example.security.CryptoRequestSigner
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The Supabase project URL and anon/publishable key configuration.
 * Hardened with:
 * - Cryptographic HMAC-SHA256 request signing on sensitive endpoints (civil-id-register, pin-login, clock in/out)
 * - Monotonic timestamp & cryptographic nonce tracking to prevent replay attacks
 * - Pluggable secure key resolution
 *
 * SUPABASE_URL / SUPABASE_ANON_KEY come from the Secrets Gradle Plugin (see .env / .env.example
 * at the project root, same convention as GEMINI_API_KEY) rather than being literals here. Note
 * that an anon key is meant to be embedded in every client of a Supabase project -- it is not a
 * secret in the way a server-side key is, and is protected by RLS/edge-function logic, not by
 * being hidden. It was still moved out of source so this repo's history stops being the most
 * convenient place to find it; it does not need "rotating" the way a real secret does.
 */
object ArtifyBackendConfig {
    // Not `const val`: BuildConfig fields are build-time values, not Kotlin compile-time
    // constants, so this must stay a regular property. (One consequence: SupabaseApi's
    // absolute-URL REST call now takes its URL via @Url instead of inside @POST("..."),
    // since annotation arguments do require an actual compile-time constant.)
    val SUPABASE_URL: String = BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String
        get() = dynamicAnonKeyProvider?.invoke() ?: BuildConfig.SUPABASE_ANON_KEY

    var dynamicAnonKeyProvider: (() -> String)? = null

    const val COMPANY_CODE = "DGO"
    private val FUNCTIONS_BASE_URL = "$SUPABASE_URL/functions/v1/"
    val ATTENDANCE_VERIFICATIONS_URL: String get() = "$SUPABASE_URL/rest/v1/attendance_verifications"

    val api: SupabaseApi by lazy { buildRetrofit().create(SupabaseApi::class.java) }

    private fun buildRetrofit(): Retrofit {
        val apiKeyInterceptor = Interceptor { chain ->
            val key = SUPABASE_ANON_KEY
            val request = chain.request().newBuilder()
                .addHeader("apikey", key)
                .build()
            chain.proceed(request)
        }

        val requestSigningInterceptor = Interceptor { chain ->
            val original = chain.request()
            val path = original.url.encodedPath
            val method = original.method

            // Sensitive endpoints that require cryptographic signing & anti-replay protection.
            // These previously checked for underscored names ("civil_id_register", "pin_login")
            // that never matched the actual hyphenated endpoint paths ("civil-id-register",
            // "pin-login"), and clock in/out go through the shared "attendance" path with the
            // action named in the JSON body rather than in the URL -- so this interceptor never
            // actually signed a single request. NOTE: the deployed edge functions do not yet
            // verify this signature either way; treat it as defense-in-depth, not a substitute
            // for the server-side identity checks.
            val bodyStringForSensitivityCheck = runCatching {
                val buffer = Buffer()
                original.body?.writeTo(buffer)
                buffer.readUtf8()
            }.getOrDefault("")
            val isSensitive = path.contains("civil-id-register") ||
                    path.contains("pin-login") ||
                    (path.contains("attendance") &&
                        (bodyStringForSensitivityCheck.contains("\"clock_in\"") ||
                         bodyStringForSensitivityCheck.contains("\"clock_out\"")))

            if (isSensitive) {
                val bodyString = bodyStringForSensitivityCheck

                val signedHeaders = CryptoRequestSigner.signRequest(
                    method = method,
                    path = path,
                    payloadJson = bodyString
                )

                val reqBuilder = original.newBuilder()
                signedHeaders.asHeaderMap().forEach { (k, v) ->
                    reqBuilder.addHeader(k, v)
                }
                chain.proceed(reqBuilder.build())
            } else {
                chain.proceed(original)
            }
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(apiKeyInterceptor)
            .addInterceptor(requestSigningInterceptor)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return Retrofit.Builder()
            .baseUrl(FUNCTIONS_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }
}
