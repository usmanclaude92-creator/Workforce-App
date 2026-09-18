package com.example.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Supabase backend connection configuration pointing to the real HCMS database.
 */
object ArtifyBackendConfig {
    const val SUPABASE_URL = "https://jpsiafvbyupofnbqonkq.supabase.co"
    const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6Impwc2lhZnZieXVwb2ZuYnFvbmtxIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODgxODIxMzAsImV4cCI6MjEwMzc1ODEzMH0.7ppmA3GRy-ABdva_A2GfrEmCgmtV5CneKBrQYwABbHM"
    const val COMPANY_CODE = "DGO"
    private const val FUNCTIONS_BASE_URL = "$SUPABASE_URL/functions/v1/"

    val api: SupabaseApi by lazy { buildRetrofit().create(SupabaseApi::class.java) }

    private fun buildRetrofit(): Retrofit {
        val apiKeyInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("apikey", SUPABASE_ANON_KEY)
                .build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(apiKeyInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return Retrofit.Builder()
            .baseUrl(FUNCTIONS_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }
}
