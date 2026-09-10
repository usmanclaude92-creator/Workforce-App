package com.example.network

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
 * - Cryptographic HMAC-SHA256 request signing on sensitive endpoints (civilIdRegister, pinLogin, clockIn, clockOut)
 * - Monotonic timestamp & cryptographic nonce tracking to prevent replay attacks
 * - Pluggable secure key resolution
 */
object ArtifyBackendConfig {
    const val SUPABASE_URL = "https://jpsiafvbyupofnbqonkq.supabase.co"
    const val DEFAULT_SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impwc2lhZnZieXVwb2ZuYnFvbmtxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgxODIxMzAsImV4cCI6MjEwMzc1ODEzMH0.7ppmA3GRy-ABdva_A2GfrEmCgmtV5CneKBrQYwABbHM"
    
    // Default key alias for backward compatibility
    val SUPABASE_ANON_KEY: String
        get() = dynamicAnonKeyProvider?.invoke() ?: DEFAULT_SUPABASE_ANON_KEY

    var dynamicAnonKeyProvider: (() -> String)? = null

    const val COMPANY_CODE = "DGO"
    private const val FUNCTIONS_BASE_URL = "$SUPABASE_URL/functions/v1/"

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

            // Sensitive endpoints that require cryptographic signing & anti-replay protection
            val isSensitive = path.contains("civil_id_register") ||
                    path.contains("pin_login") ||
                    path.contains("clock_in") ||
                    path.contains("clock_out")

            if (isSensitive) {
                val bodyString = runCatching {
                    val buffer = Buffer()
                    original.body?.writeTo(buffer)
                    buffer.readUtf8()
                }.getOrDefault("")

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
