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
     * Verifies that the uploaded selfie is accessible in object storage (HTTP 200).
     */
    suspend fun verifySelfieAccessible(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "Verification check failed for $url: ${e.message}")
            false
        }
    }

    /**
     * Deletes a selfie from Supabase object storage (and local cache if applicable).
     * Used during shift-start selfie replacement to prevent orphan files and bloat.
     */
    suspend fun deleteSelfieFile(urlOrPath: String?): Result<Unit> = withContext(Dispatchers.IO) {
        if (urlOrPath.isNullOrBlank()) return@withContext Result.success(Unit)
        try {
            // Delete local file if it exists on disk
            try {
                val localFile = File(urlOrPath)
                if (localFile.exists()) {
                    localFile.delete()
                    Log.d(TAG, "Deleted local selfie cache: $urlOrPath")
                }
            } catch (e: Exception) {
                // Ignore local file deletion failure
            }

            // If it's a Supabase storage URL or path, remove from remote bucket
            val fileName = extractFileNameFromUrl(urlOrPath)
            if (!fileName.isNullOrBlank()) {
                val token = sessionStore.cachedAccessToken()
                val authHeader = if (!token.isNullOrBlank()) "Bearer $token" else "Bearer ${ArtifyBackendConfig.SUPABASE_ANON_KEY}"
                val targetUrl = "${ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/$BUCKET_NAME/$fileName"

                val request = Request.Builder()
                    .url(targetUrl)
                    .addHeader("Authorization", authHeader)
                    .addHeader("apikey", ArtifyBackendConfig.SUPABASE_ANON_KEY)
                    .delete()
                    .build()

                val response = httpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful || resp.code == 404) {
                        Log.i(TAG, "Successfully deleted remote selfie: $fileName")
                    } else {
                        Log.w(TAG, "Remote delete returned HTTP ${resp.code} for $fileName")
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting old selfie: ${e.message}")
            Result.failure(e)
        }
    }

    private fun extractFileNameFromUrl(urlOrPath: String): String? {
        return when {
            urlOrPath.contains("/storage/v1/object/public/$BUCKET_NAME/") ->
                urlOrPath.substringAfter("/storage/v1/object/public/$BUCKET_NAME/")
            urlOrPath.contains("/storage/v1/object/$BUCKET_NAME/") ->
                urlOrPath.substringAfter("/storage/v1/object/$BUCKET_NAME/")
            urlOrPath.startsWith("$BUCKET_NAME/") ->
                urlOrPath.removePrefix("$BUCKET_NAME/")
            urlOrPath.startsWith("http://") || urlOrPath.startsWith("https://") ->
                urlOrPath.substringAfterLast("/")
            !urlOrPath.contains("/") -> urlOrPath
            else -> null
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
