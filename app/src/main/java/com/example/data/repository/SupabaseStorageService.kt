package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.network.ArtifyBackendConfig
import com.example.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads compressed selfies directly to Supabase Storage (attendance-selfies bucket)
 * to avoid bloating database tables and JSON payloads with large Base64 strings.
 */
class SupabaseStorageService(
    private val context: Context,
    private val sessionStore: SecureSessionStore = SecureSessionStore(context)
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "SupabaseStorageService"
        const val BUCKET_NAME = "attendance-selfies"
    }

    /**
     * Compresses the selfie image file and uploads it to Supabase Storage.
     * Returns the permanent public access URL if successful.
     */
    suspend fun uploadSelfieFile(
        file: File,
        customFileName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                return@withContext Result.failure(IllegalArgumentException("File does not exist: ${file.absolutePath}"))
            }

            // Optimize resolution and compress
            val compressedBytes = compressImageForUpload(file.absolutePath)
                ?: file.readBytes()

            val fileName = customFileName ?: "selfie_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
            uploadBytes(compressedBytes, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload selfie file: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads raw compressed JPEG bytes to Supabase Storage.
     */
    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = sessionStore.cachedAccessToken()
            val authHeader = if (!token.isNullOrBlank()) "Bearer $token" else "Bearer ${ArtifyBackendConfig.SUPABASE_ANON_KEY}"

            val targetUrl = "${ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/$BUCKET_NAME/$fileName"
            val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("Authorization", authHeader)
                .addHeader("apikey", ArtifyBackendConfig.SUPABASE_ANON_KEY)
                .addHeader("x-upsert", "true")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val publicUrl = "${ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/public/$BUCKET_NAME/$fileName"
                    Log.i(TAG, "Successfully uploaded selfie to Supabase Storage: $publicUrl (${bytes.size / 1024} KB)")
                    Result.success(publicUrl)
                } else {
                    val errorBody = resp.body?.string().orEmpty()
                    Log.w(TAG, "Supabase Storage upload returned HTTP ${resp.code}: $errorBody")
                    Result.failure(Exception("Storage upload failed with HTTP ${resp.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network error during Supabase Storage upload: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Resizes the image to max 720p with 80% JPEG compression (~40-80 KB),
     * drastically reducing upload bandwidth over field cellular networks.
     */
    private fun compressImageForUpload(filePath: String): ByteArray? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, boundsOptions)
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            val maxDimension = 720
            var sampleSize = 1
            var w = origWidth
            var h = origHeight
            while (w / 2 >= maxDimension || h / 2 >= maxDimension) {
                w /= 2
                h /= 2
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val sampledBitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

            val outputStream = ByteArrayOutputStream()
            sampledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            sampledBitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Image compression error: ${e.message}")
            null
        }
    }
}
