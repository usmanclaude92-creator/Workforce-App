package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.data.AppDatabase
import com.example.data.entity.AttendanceRecordEntity
import com.example.data.repository.BackendAuthRepository
import com.example.data.repository.BackendResult
import com.example.data.repository.BackendWorkforceRepository
import com.example.data.repository.SupabaseStorageService
import com.example.security.SecureSessionStore
import com.example.util.ImageCompressionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Android WorkManager background worker dedicated to synchronizing offline attendance logs
 * stored in the Room 'attendance_records' table (mirroring Supabase) once network connectivity
 * is restored.
 *
 * Key guarantees:
 * 1. Automatic Execution: Runs immediately when Android OS detects active network connectivity.
 * 2. Process Survival: Guaranteed execution even if the app was swiped away or the device was rebooted.
 * 3. Idempotent Retry: Uses client_event_id to prevent duplicate shift logs.
 * 4. Two-Phase Upload: Uploads biometric selfie files to Supabase Storage first, then commits the shift.
 * 5. Storage Pruning: Removes temporary local selfie images upon confirmed sync.
 */
class OfflineAttendanceRecordSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val db = AppDatabase.getInstance(appContext)
    private val dao = db.attendanceRecordDao()
    private val sessionStore = SecureSessionStore(appContext)
    private val authRepo = BackendAuthRepository(sessionStore)
    private val repository = BackendWorkforceRepository(authRepo, sessionStore)
    private val storageService = SupabaseStorageService(appContext, sessionStore)

    companion object {
        private const val TAG = "OfflineAttendanceSync"
        const val UNIQUE_WORK_NAME = "offline_attendance_records_sync_work"
        const val PERIODIC_WORK_NAME = "offline_attendance_records_periodic_sync"

        /**
         * Enqueues an immediate one-time sync task with CONNECTED network constraints.
         * WorkManager will hold this task until the OS detects an active internet connection.
         */
        fun enqueueWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OfflineAttendanceRecordSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Enqueued unique one-time offline attendance sync task.")
        }

        /**
         * Schedules periodic background sync (every 15 min) with network constraint
         * as a reliable safety net.
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<OfflineAttendanceRecordSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d(TAG, "Scheduled periodic offline attendance sync task.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting OfflineAttendanceRecordSyncWorker execution...")
        var hadTransientErrors = false

        try {
            val unsyncedList = dao.getAllUnsyncedRecords()
            Log.d(TAG, "Found ${unsyncedList.size} pending attendance records in 'attendance_records' table.")

            for (record in unsyncedList) {
                // Skip if permanently failed
                if (record.syncStatus == "FAILED") continue

                val now = System.currentTimeMillis()
                if (record.nextRetryAtEpochMs > now) continue

                val syncSuccess = syncSingleRecord(record)
                if (!syncSuccess) {
                    hadTransientErrors = true
                }
            }

            // Prune synced records older than 7 days to conserve device storage
            val weekAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            dao.pruneSyncedRecords(weekAgoMs)

            if (hadTransientErrors) {
                Log.w(TAG, "Worker completed with transient errors; scheduling retry.")
                Result.retry()
            } else {
                Log.i(TAG, "All pending attendance records synced successfully.")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error executing OfflineAttendanceRecordSyncWorker: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun syncSingleRecord(record: AttendanceRecordEntity): Boolean {
        // Mark as SYNCING in Room
        dao.updateRecord(record.copy(syncStatus = "SYNCING"))

        // Step 1: Upload local selfie image to Supabase Storage if present
        var uploadedSelfieUrl: String? = record.selfieUrl
        val localPath = record.selfieLocalPath
        if (uploadedSelfieUrl.isNullOrBlank() && !localPath.isNullOrBlank()) {
            val file = File(localPath)
            if (file.exists()) {
                val uploadResult = storageService.uploadSelfieFile(file)
                if (uploadResult.isSuccess) {
                    uploadedSelfieUrl = uploadResult.getOrNull()
                }
            }
        }

        // Fallback to compressed base64 if cloud storage URL is unavailable
        val selfiePayload = uploadedSelfieUrl ?: localPath?.let {
            ImageCompressionUtils.compressAndEncodeSelfie(it)
        }

        // Step 2: Synchronize attendance log with backend
        val isClockIn = record.action.equals("CLOCK_IN", ignoreCase = true)
        val result = if (isClockIn) {
            repository.clockIn(
                clientEventId = record.clientEventId,
                deviceTimestamp = record.deviceTimestamp,
                latitude = record.latitude,
                longitude = record.longitude,
                accuracy = record.gpsAccuracyMeters,
                isMockLocation = record.isMockLocation,
                selfieBase64 = selfiePayload
            )
        } else {
            repository.clockOut(
                clientEventId = record.clientEventId,
                deviceTimestamp = record.deviceTimestamp,
                latitude = record.latitude,
                longitude = record.longitude,
                accuracy = record.gpsAccuracyMeters,
                isMockLocation = record.isMockLocation,
                selfieBase64 = selfiePayload
            )
        }

        return when (result) {
            is BackendResult.Success -> {
                val now = System.currentTimeMillis()
                dao.markAsSynced(
                    id = record.id,
                    syncedAt = now,
                    selfieUrl = uploadedSelfieUrl
                )
                // Purge local temp image file to reclaim disk space
                localPath?.let { path ->
                    runCatching { File(path).delete() }
                }
                Log.i(TAG, "Synced record ${record.id} (${record.action}) successfully.")
                true
            }
            is BackendResult.Failure -> {
                val attempts = record.syncAttempts + 1
                val isNetworkError = result.isNetworkError
                val newStatus = if (isNetworkError) "PENDING_SYNC" else "FAILED"
                val delaySeconds = min(30.0 * 2.0.pow(attempts - 1), 1800.0)
                val nextRetry = System.currentTimeMillis() + (delaySeconds * 1000).toLong()

                dao.updateSyncFailure(
                    id = record.id,
                    status = newStatus,
                    attempts = attempts,
                    error = result.message,
                    nextRetry = nextRetry
                )
                Log.w(TAG, "Sync failed for ${record.id}: ${result.message} (status=$newStatus, attempt=$attempts)")
                false
            }
        }
    }
}
