package com.example.data.sync

import android.content.Context
import android.util.Base64
import com.example.data.repository.BackendResult
import com.example.data.repository.BackendWorkforceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

data class SyncQueueStatus(
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val lastSyncAtEpochMs: Long? = null,
    val lastError: String? = null,
    val isOnline: Boolean = true
)

/**
 * Reliable offline queue + sync for the real attendance/leave flow.
 *
 * - Writes go to Room first (PENDING) whenever an immediate online attempt
 *   fails for a genuine network reason.
 * - A background collector re-runs [syncNow] whenever connectivity returns;
 *   the UI can also call it directly ("Sync Now").
 * - Failures are retried with exponential backoff; a non-network rejection
 *   (e.g. a real business-rule error) is marked FAILED, not silently retried
 *   forever, and is never reported to the user as synced.
 * - Attendance events sync strictly in queued order (a clock-out cannot
 *   legitimately land before its clock-in), so the batch stops at the first
 *   item that isn't yet ready or that failed.
 */
class RealSyncManager(
    private val context: Context,
    private val repository: BackendWorkforceRepository,
    private val networkMonitor: NetworkMonitor,
    private val employeeId: String
) {
    private val dao = RealSyncDatabase.getInstance(context).pendingSyncDao()
    private val syncMutex = Mutex()

    private val _status = MutableStateFlow(SyncQueueStatus(isOnline = networkMonitor.isOnlineNow()))
    val status: StateFlow<SyncQueueStatus> = _status.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch {
            networkMonitor.observe().collect { online ->
                _status.value = _status.value.copy(isOnline = online)
                if (online) syncNow()
            }
        }
    }

    /** The still-unsynced clock-in a queued clock-out (if any) hasn't superseded yet — lets the UI restore "shift in progress" after an app restart before the queue has synced. */
    suspend fun findQueuedOpenClockIn(): PendingAttendanceEventEntity? {
        val items = dao.getUnsyncedAttendanceEvents(employeeId)
        val lastClockOut = items.lastOrNull { it.action == "clock_out" }
        return items.lastOrNull { it.action == "clock_in" && (lastClockOut == null || it.queuedAtEpochMs > lastClockOut.queuedAtEpochMs) }
    }

    suspend fun refreshCounts() {
        val pendingAttendance = dao.getUnsyncedAttendanceEvents(employeeId)
        val pendingLeave = dao.getUnsyncedLeaveRequests(employeeId)
        val pending = pendingAttendance.count { it.syncStatus == SyncStatus.PENDING } + pendingLeave.count { it.syncStatus == SyncStatus.PENDING }
        val failed = pendingAttendance.count { it.syncStatus == SyncStatus.FAILED } + pendingLeave.count { it.syncStatus == SyncStatus.FAILED }
        _status.value = _status.value.copy(pendingCount = pending, failedCount = failed)
    }

    suspend fun queueClockEvent(
        clientEventId: String, action: String, deviceTimestamp: String,
        latitude: Double?, longitude: Double?, accuracy: Float?, isMockLocation: Boolean, selfieLocalPath: String?
    ) {
        val durablePath = selfieLocalPath?.let { copySelfieToDurableStorage(it) }
        dao.insertAttendanceEvent(
            PendingAttendanceEventEntity(
                clientEventId = clientEventId, employeeId = employeeId, action = action, deviceTimestamp = deviceTimestamp,
                latitude = latitude, longitude = longitude, gpsAccuracyMeters = accuracy, isMockLocation = isMockLocation,
                selfieLocalPath = durablePath, queuedAtEpochMs = System.currentTimeMillis(), syncStatus = SyncStatus.PENDING
            )
        )
        refreshCounts()
    }

    suspend fun queueLeaveRequest(clientRequestId: String, leaveType: String, startDate: String, endDate: String, reason: String) {
        dao.insertLeaveRequest(
            PendingLeaveRequestEntity(
                clientRequestId = clientRequestId, employeeId = employeeId, leaveType = leaveType, startDate = startDate,
                endDate = endDate, reason = reason, queuedAtEpochMs = System.currentTimeMillis(), syncStatus = SyncStatus.PENDING
            )
        )
        refreshCounts()
    }

    /** [force] ignores backoff timers — used by the user-facing "Sync Now" action. */
    suspend fun syncNow(force: Boolean = false) {
        if (syncMutex.isLocked) return // a sync is already running
        syncMutex.withLock {
            if (!networkMonitor.isOnlineNow()) {
                _status.value = _status.value.copy(isOnline = false, lastError = "Offline — will retry automatically once connected.")
                return
            }
            _status.value = _status.value.copy(isSyncing = true, isOnline = true)
            var lastError: String? = null

            val attendanceQueue = dao.getUnsyncedAttendanceEvents(employeeId)
            for (item in attendanceQueue) {
                if (item.syncStatus == SyncStatus.FAILED && !force) break
                val now = System.currentTimeMillis()
                if (!force && item.nextRetryAtEpochMs > now) break
                val outcome = syncAttendanceEvent(item)
                if (!outcome) { lastError = _status.value.lastError; break }
            }

            val leaveQueue = dao.getUnsyncedLeaveRequests(employeeId)
            for (item in leaveQueue) {
                if (item.syncStatus == SyncStatus.FAILED && !force) break
                val now = System.currentTimeMillis()
                if (!force && item.nextRetryAtEpochMs > now) break
                val outcome = syncLeaveRequest(item)
                if (!outcome) { lastError = _status.value.lastError; break }
            }

            refreshCounts()
            _status.value = _status.value.copy(isSyncing = false, lastSyncAtEpochMs = System.currentTimeMillis(), lastError = lastError)
        }
    }

    /** Returns true if this item is now synced (or was already), false if the batch should stop here. */
    private suspend fun syncAttendanceEvent(item: PendingAttendanceEventEntity): Boolean {
        dao.updateAttendanceEvent(item.copy(syncStatus = SyncStatus.SYNCING))
        val selfieBase64 = item.selfieLocalPath?.let { encodeSelfieFile(it) }
        val result = if (item.action == "clock_in") {
            repository.clockIn(item.clientEventId, item.deviceTimestamp, item.latitude, item.longitude, item.gpsAccuracyMeters, item.isMockLocation, selfieBase64)
        } else {
            repository.clockOut(item.clientEventId, item.deviceTimestamp, item.latitude, item.longitude, item.gpsAccuracyMeters, item.isMockLocation, selfieBase64)
        }
        return when (result) {
            is BackendResult.Success -> {
                dao.updateAttendanceEvent(item.copy(syncStatus = SyncStatus.SYNCED, syncedAtEpochMs = System.currentTimeMillis(), lastError = null))
                item.selfieLocalPath?.let { runCatching { File(it).delete() } }
                uploadAttendanceEventToFirestore(item)
                true
            }
            is BackendResult.Failure -> {
                val attempts = item.attempts + 1
                val status = if (result.isNetworkError) SyncStatus.PENDING else SyncStatus.FAILED
                dao.updateAttendanceEvent(
                    item.copy(syncStatus = status, attempts = attempts, lastError = result.message, nextRetryAtEpochMs = backoffTimestamp(attempts))
                )
                _status.value = _status.value.copy(lastError = result.message)
                false
            }
        }
    }

    private suspend fun syncLeaveRequest(item: PendingLeaveRequestEntity): Boolean {
        dao.updateLeaveRequest(item.copy(syncStatus = SyncStatus.SYNCING))
        val result = repository.submitLeave(item.clientRequestId, item.leaveType, item.startDate, item.endDate, item.reason)
        return when (result) {
            is BackendResult.Success -> {
                dao.updateLeaveRequest(item.copy(syncStatus = SyncStatus.SYNCED, syncedAtEpochMs = System.currentTimeMillis(), lastError = null))
                true
            }
            is BackendResult.Failure -> {
                val attempts = item.attempts + 1
                val status = if (result.isNetworkError) SyncStatus.PENDING else SyncStatus.FAILED
                dao.updateLeaveRequest(
                    item.copy(syncStatus = status, attempts = attempts, lastError = result.message, nextRetryAtEpochMs = backoffTimestamp(attempts))
                )
                _status.value = _status.value.copy(lastError = result.message)
                false
            }
        }
    }

    /** Exponential backoff capped at 30 minutes: 30s, 60s, 120s, ... */
    private fun backoffTimestamp(attempts: Int): Long {
        val delaySeconds = min(30.0 * 2.0.pow(attempts - 1), 30 * 60.0)
        return System.currentTimeMillis() + (delaySeconds * 1000).toLong()
    }

    private suspend fun copySelfieToDurableStorage(sourcePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "pending_selfies").apply { mkdirs() }
            val dest = File(dir, "${UUID.randomUUID()}.jpg")
            File(sourcePath).copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun encodeSelfieFile(path: String): String? {
        return com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(path)
    }

    private fun uploadAttendanceEventToFirestore(item: PendingAttendanceEventEntity) {
        try {
            val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val isClockIn = item.action == "clock_in"
            val actionCollection = if (isClockIn) "clock_ins" else "clock_outs"
            val payload = mapOf(
                "eventId" to item.clientEventId,
                "attendanceId" to item.clientEventId,
                "employeeId" to item.employeeId,
                "action" to item.action,
                "recordType" to if (isClockIn) "CHECK_IN" else "CHECK_OUT",
                "deviceTimestamp" to item.deviceTimestamp,
                "latitude" to item.latitude,
                "longitude" to item.longitude,
                "gpsAccuracyMeters" to item.gpsAccuracyMeters,
                "isMockLocation" to item.isMockLocation,
                "queuedAtEpochMs" to item.queuedAtEpochMs,
                "syncedAtEpochMs" to System.currentTimeMillis(),
                "syncSource" to "ROOM_REAL_SYNC_V2"
            )
            firestore.collection("attendance_records").document(item.clientEventId)
                .set(payload, com.google.firebase.firestore.SetOptions.merge())
            firestore.collection(actionCollection).document(item.clientEventId)
                .set(payload, com.google.firebase.firestore.SetOptions.merge())
        } catch (e: Exception) {
            // Non-blocking firestore sync mirror
        }
    }
}
