package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * High-efficiency image downsampling, orientation correction, and JPEG compression utility.
 * Reduces raw multi-megabyte camera captures down to a compact, bandwidth-friendly payload (~50-90 KB)
 * before Base64 serialization, eliminating GC churn and Out-of-Memory risks on low-end hardware.
 */
object ImageCompressionUtils {
    /**
     * Reads an image file from disk, correctly rotates it according to EXIF data,
     * downscales it so that neither width nor height exceeds [maxDimension],
     * and compresses it as a JPEG at the given [quality] percentage.
     * Returns a compact Base64-encoded string, or null on error.
     */
    suspend fun compressAndEncodeSelfie(
        filePath: String,
        maxDimension: Int = 720,
        quality: Int = 75
    ): String? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return@withContext null

        try {
            // Step 1: Decode image dimensions without loading full pixels into memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

            // Step 2: Calculate inSampleSize to avoid large memory allocations
            var sampleSize = 1
            val maxSide = max(origWidth, origHeight)
            while (maxSide / (sampleSize * 2) >= maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // 16-bit color halves heap memory vs ARGB_8888
            }
            val decodedBitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return@withContext null

            // Step 3: Check EXIF orientation and compute rotation matrix
            val orientation = runCatching {
                val exif = ExifInterface(filePath)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            // Step 4: Scale down precisely to maxDimension if still larger
            val currentMax = max(decodedBitmap.width, decodedBitmap.height)
            val scale = if (currentMax > maxDimension) maxDimension.toFloat() / currentMax else 1.0f

            val matrix = Matrix().apply {
                if (scale < 1.0f) postScale(scale, scale)
                if (rotationDegrees != 0f) postRotate(rotationDegrees)
            }

            val finalBitmap = if (matrix.isIdentity) {
                decodedBitmap
            } else {
                Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true).also {
                    if (it != decodedBitmap) decodedBitmap.recycle()
                }
            }

            // Step 5: Compress to JPEG
            val byteStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteStream)
            finalBitmap.recycle()

            Base64.encodeToString(byteStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
