package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.repository.BackendAuthRepository
import com.example.data.repository.BackendResult
import com.example.data.repository.BackendWorkforceRepository
import com.example.data.repository.SupabaseStorageService
import com.example.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Robust background worker managed by Android WorkManager.
 * Guarantees that queued offline clock-in/out events and leave requests
 * are reliably synced to Supabase / Backend even if the application process was
 * killed or the device restarted.
 */
class AttendanceSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val dao = RealSyncDatabase.getInstance(appContext).pendingSyncDao()
    private val sessionStore = SecureSessionStore(appContext)
    private val authRepo = BackendAuthRepository(sessionStore)
    private val repository = BackendWorkforceRepository(authRepo, sessionStore)
    private val storageService = SupabaseStorageService(appContext, sessionStore)

    companion object {
        private const val TAG = "AttendanceSyncWorker"
        const val UNIQUE_ONE_TIME_WORK = "attendance_sync_one_time"
        const val UNIQUE_PERIODIC_WORK = "attendance_sync_periodic"

        /**
         * Enqueues an immediate one-time sync task triggered when new attendance records
         * are queued offline.
         */
        fun enqueueOneTimeWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Enqueued unique one-time attendance sync worker.")
        }

        /**
         * Schedules a periodic sync task running every 15 minutes to reconcile any pending
         * offline attendance logs.
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            Log.d(TAG, "Scheduled periodic attendance sync worker.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting AttendanceSyncWorker execution...")
        var hadTransientErrors = false

        try {
            // 1. Process pending attendance events
            val attendanceItems = dao.getAllUnsyncedAttendanceEvents()
            Log.d(TAG, "Found ${attendanceItems.size} pending attendance events in database.")

            for (item in attendanceItems) {
                if (item.syncStatus == SyncStatus.FAILED) continue
                val now = System.currentTimeMillis()
                if (item.nextRetryAtEpochMs > now) continue

                val success = syncAttendanceItem(item)
                if (!success) {
                    hadTransientErrors = true
                }
            }

            // 2. Process pending leave requests
            val leaveItems = dao.getAllUnsyncedLeaveRequests()
            for (leave in leaveItems) {
                if (leave.syncStatus == SyncStatus.FAILED) continue
                val now = System.currentTimeMillis()
                if (leave.nextRetryAtEpochMs > now) continue

                val success = syncLeaveItem(leave)
                if (!success) {
                    hadTransientErrors = true
                }
            }

            // 3. Prune old synced events (older than 7 days)
            val weekAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            dao.pruneSyncedAttendanceEvents(weekAgoMs)
            dao.pruneSyncedLeaveRequests(weekAgoMs)

            if (hadTransientErrors) {
                Log.w(TAG, "Worker completed with some transient errors. Requesting retry.")
                Result.retry()
            } else {
                Log.i(TAG, "All pending attendance items synced successfully.")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal exception in AttendanceSyncWorker: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun syncAttendanceItem(item: PendingAttendanceEventEntity): Boolean {
        dao.updateAttendanceEvent(item.copy(syncStatus = SyncStatus.SYNCING))

        // Step A: If a local selfie file exists, attempt uploading directly to Supabase Storage
        var selfieUrl: String? = null
        val localPath = item.selfieLocalPath
        if (!localPath.isNullOrBlank()) {
            val file = File(localPath)
            if (file.exists()) {
                val uploadResult = storageService.uploadSelfieFile(file)
                if (uploadResult.isSuccess) {
                    selfieUrl = uploadResult.getOrNull()
                }
            }
        }

        // Step B: Encode compressed base64 only if storage upload was not available
        val selfiePayload = selfieUrl ?: localPath?.let {
            com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(it)
        }

        val result = if (item.action == "clock_in") {
            repository.clockIn(
                clientEventId = item.clientEventId,
                deviceTimestamp = item.deviceTimestamp,
                latitude = item.latitude,
                longitude = item.longitude,
                accuracy = item.gpsAccuracyMeters,
                isMockLocation = item.isMockLocation,
                selfieBase64 = selfiePayload
            )
        } else {
            repository.clockOut(
                clientEventId = item.clientEventId,
                deviceTimestamp = item.deviceTimestamp,
                latitude = item.latitude,
                longitude = item.longitude,
                accuracy = item.gpsAccuracyMeters,
                isMockLocation = item.isMockLocation,
                selfieBase64 = selfiePayload
            )
        }

        return when (result) {
            is BackendResult.Success -> {
                dao.updateAttendanceEvent(
                    item.copy(
                        syncStatus = SyncStatus.SYNCED,
                        syncedAtEpochMs = System.currentTimeMillis(),
                        lastError = null
                    )
                )
                // Clean up local temp selfie file after successful cloud ingestion
                item.selfieLocalPath?.let { path ->
                    runCatching { File(path).delete() }
                }
                true
            }
            is BackendResult.Failure -> {
                val attempts = item.attempts + 1
                val newStatus = if (result.isNetworkError) SyncStatus.PENDING else SyncStatus.FAILED
                val delaySeconds = min(30.0 * 2.0.pow(attempts - 1), 30 * 60.0)
                val nextRetry = System.currentTimeMillis() + (delaySeconds * 1000).toLong()

                dao.updateAttendanceEvent(
                    item.copy(
                        syncStatus = newStatus,
                        attempts = attempts,
                        lastError = result.message,
                        nextRetryAtEpochMs = nextRetry
                    )
                )
                false
            }
        }
    }

    private suspend fun syncLeaveItem(item: PendingLeaveRequestEntity): Boolean {
        dao.updateLeaveRequest(item.copy(syncStatus = SyncStatus.SYNCING))
        val result = repository.submitLeave(
            clientRequestId = item.clientRequestId,
            leaveType = item.leaveType,
            startDate = item.startDate,
            endDate = item.endDate,
            reason = item.reason
        )
        return when (result) {
            is BackendResult.Success -> {
                dao.updateLeaveRequest(
                    item.copy(
                        syncStatus = SyncStatus.SYNCED,
                        syncedAtEpochMs = System.currentTimeMillis(),
                        lastError = null
                    )
                )
                true
            }
            is BackendResult.Failure -> {
                val attempts = item.attempts + 1
                val newStatus = if (result.isNetworkError) SyncStatus.PENDING else SyncStatus.FAILED
                val delaySeconds = min(30.0 * 2.0.pow(attempts - 1), 30 * 60.0)
                val nextRetry = System.currentTimeMillis() + (delaySeconds * 1000).toLong()

                dao.updateLeaveRequest(
                    item.copy(
                        syncStatus = newStatus,
                        attempts = attempts,
                        lastError = result.message,
                        nextRetryAtEpochMs = nextRetry
                    )
                )
                false
            }
        }
    }

    // NOTE: this used to also mirror every synced real attendance event into the Firestore
    // "attendance_records" collection that the legacy Demo-mode path (FirestoreSyncManager) writes
    // to. Nothing in the Real/HCMS flow read that mirror back, so it was a pure duplicate write that
    // mixed production attendance data into a Demo-only store. Removed — see RealSyncManager for the
    // matching removal on the foreground sync path.
}
