package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackendResult
import com.example.data.repository.IWorkforceRepository
import com.example.data.repository.SupabaseStorageService
import com.example.data.sync.OfflineCache
import com.example.data.sync.RealSyncManager
import com.example.data.sync.SyncQueueStatus
import com.example.location.LocationHelper
import com.example.network.ArtifyBackendConfig
import com.example.network.AttendanceEventSummary
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NotificationDto
import com.example.network.ProfileDto
import com.example.network.ShiftCompletionLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A shift id prefixed this way represents a queued, not-yet-synced local clock-in — never a real server row. */
const val LOCAL_PENDING_SHIFT_PREFIX = "local-pending-"

data class RealWorkerUiState(
    val activeShift: AttendanceShiftDto? = null,
    val shiftHistory: List<AttendanceShiftDto> = emptyList(),
    val completionLogs: List<ShiftCompletionLog> = emptyList(),
    val leaveHistory: List<LeaveRequestDto> = emptyList(),
    val isProcessing: Boolean = false,
    val isLoading: Boolean = true,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val showStartShiftDialog: Boolean = false,
    val showEndShiftDialog: Boolean = false,
    val syncQueue: SyncQueueStatus = SyncQueueStatus(),
    val notifications: List<NotificationDto> = emptyList(),
    val profile: ProfileDto? = null,
    val localAvatarPath: String? = null,
    val selfieUrlCache: Map<String, String> = emptyMap(),
    val shiftDurationFormatted: String = "00:00:00"
)

/** Drives the real (backend-authenticated) worker attendance/leave flow, with offline queueing. */
class RealWorkerViewModel(
    private val repository: IWorkforceRepository,
    private val locationHelper: LocationHelper,
    private val syncManager: RealSyncManager,
    private val offlineCache: OfflineCache,
    private val employeeId: String,
    private val storageService: SupabaseStorageService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(RealWorkerUiState())
    val uiState: StateFlow<RealWorkerUiState> = _uiState.asStateFlow()

    private val _shiftDurationFormatted = MutableStateFlow("00:00:00")
    val shiftDurationFormatted: StateFlow<String> = _shiftDurationFormatted.asStateFlow()

    /** True while a clock-in has been queued locally and hasn't synced yet — clock-out must also queue until it does. */
    private var pendingLocalClockIn = false

    init {
        syncManager.start(viewModelScope)
        viewModelScope.launch {
            syncManager.status.collect { status ->
                val wasPending = pendingLocalClockIn
                _uiState.value = _uiState.value.copy(syncQueue = status)
                if (status.pendingCount == 0 && status.failedCount == 0 && !status.isSyncing) {
                    pendingLocalClockIn = false
                    if (wasPending) refresh() // queue just drained — replace the local placeholder with server truth
                }
            }
        }
        viewModelScope.launch {
            syncManager.refreshCounts()
            val queuedOpenClockIn = syncManager.findQueuedOpenClockIn()
            if (queuedOpenClockIn != null) {
                pendingLocalClockIn = true
                _uiState.value = _uiState.value.copy(activeShift = localPendingShift(queuedOpenClockIn.clientEventId), isLoading = false)
            } else {
                refresh()
            }
        }
        startLiveShiftTimer()
    }

    private fun startLiveShiftTimer() {
        viewModelScope.launch {
            while (isActive) {
                val startIso = _uiState.value.activeShift?.clockIn?.serverTimestamp
                val startInstant = startIso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
                val formatted = if (startInstant != null) {
                    val diffSec = (Instant.now().epochSecond - startInstant.epochSecond).coerceAtLeast(0)
                    val hours = diffSec / 3600
                    val mins = (diffSec % 3600) / 60
                    val secs = diffSec % 60
                    String.format("%02d:%02d:%02d", hours, mins, secs)
                } else {
                    "00:00:00"
                }
                _shiftDurationFormatted.value = formatted
                delay(1000L)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val cachedCompletionLogs = offlineCache.getCachedShiftCompletionLogs(employeeId) ?: emptyList()
            if (!pendingLocalClockIn) {
                when (val result = repository.myShifts()) {
                    is BackendResult.Success -> {
                        offlineCache.cacheShifts(employeeId, result.value)
                        val active = result.value.firstOrNull { it.status == "OPEN" }
                        val history = result.value
                            .filter { it.status != "OPEN" }
                            .sortedWith(
                                compareByDescending<AttendanceShiftDto> {
                                    it.clockIn?.serverTimestamp ?: it.clockOut?.serverTimestamp ?: (it.shiftDate + "T00:00:00")
                                }.thenByDescending { it.shiftDate }
                            )
                            .take(30)
                        val serverLogs = history.map { shift ->
                            ShiftCompletionLog(
                                logId = "LOG-${shift.id.takeLast(8)}",
                                shiftId = shift.id,
                                employeeId = shift.employeeId,
                                employeeName = shift.employee?.fullName ?: _uiState.value.profile?.fullName ?: "Worker",
                                projectName = shift.project?.name ?: "Assigned Site",
                                shiftDate = shift.shiftDate,
                                clockInTime = shift.clockIn?.serverTimestamp,
                                clockOutTime = shift.clockOut?.serverTimestamp ?: (shift.shiftDate + "T17:00:00Z"),
                                totalWorkedMinutes = shift.totalWorkedMinutes ?: 0,
                                selfieUrl = shift.clockOut?.selfieStoragePath ?: shift.clockIn?.selfieStoragePath,
                                latitude = null,
                                longitude = null,
                                gpsAccuracyMeters = null,
                                isMockLocation = shift.clockOut?.isMockLocation ?: false,
                                status = shift.status,
                                supabaseSyncStatus = "STORED_IN_SUPABASE",
                                completedAtUtc = runCatching {
                                    Instant.parse(shift.clockOut?.serverTimestamp ?: (shift.shiftDate + "T17:00:00Z")).toEpochMilli()
                                }.getOrDefault(System.currentTimeMillis()),
                                supervisorReview = shift.reviewComment
                            )
                        }
                        val mergedLogs = (cachedCompletionLogs + serverLogs).distinctBy { it.shiftId }
                        offlineCache.cacheShiftCompletionLogs(employeeId, mergedLogs)
                        _uiState.value = _uiState.value.copy(
                            activeShift = active,
                            shiftHistory = history,
                            completionLogs = mergedLogs,
                            isLoading = false
                        )
                    }
                    is BackendResult.Failure -> {
                        val cached = if (result.isNetworkError) offlineCache.getCachedShifts(employeeId) else null
                        if (cached != null) {
                            val active = cached.firstOrNull { it.status == "OPEN" }
                            val history = cached
                                .filter { it.status != "OPEN" }
                                .sortedWith(
                                    compareByDescending<AttendanceShiftDto> {
                                        it.clockIn?.serverTimestamp ?: it.clockOut?.serverTimestamp ?: (it.shiftDate + "T00:00:00")
                                    }.thenByDescending { it.shiftDate }
                                )
                                .take(30)
                            _uiState.value = _uiState.value.copy(
                                activeShift = active,
                                shiftHistory = history,
                                completionLogs = cachedCompletionLogs,
                                isLoading = false
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                completionLogs = cachedCompletionLogs,
                                errorMessage = result.message
                            )
                        }
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, completionLogs = cachedCompletionLogs)
            }
            when (val result = repository.myLeaveRequests()) {
                is BackendResult.Success -> {
                    offlineCache.cacheLeave(employeeId, result.value)
                    _uiState.value = _uiState.value.copy(leaveHistory = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedLeave(employeeId)?.let { _uiState.value = _uiState.value.copy(leaveHistory = it) }
                }
            }
            when (val result = repository.myNotifications()) {
                is BackendResult.Success -> {
                    offlineCache.cacheNotifications(employeeId, result.value)
                    _uiState.value = _uiState.value.copy(notifications = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedNotifications(employeeId)?.let { _uiState.value = _uiState.value.copy(notifications = it) }
                }
            }
            when (val result = repository.myProfile()) {
                is BackendResult.Success -> {
                    offlineCache.cacheProfile(employeeId, result.value)
                    _uiState.value = _uiState.value.copy(profile = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError && _uiState.value.profile == null) {
                        offlineCache.getCachedProfile(employeeId)?.let { _uiState.value = _uiState.value.copy(profile = it) }
                    }
                }
            }
        }
    }

    fun setStartShiftDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showStartShiftDialog = show) }
    fun setEndShiftDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showEndShiftDialog = show) }
    fun clearFeedback() { _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null) }
    fun syncNow() { viewModelScope.launch { syncManager.syncNow(force = true) } }

    fun updateProfilePhoto(filePath: String) {
        _uiState.value = _uiState.value.copy(
            localAvatarPath = filePath,
            statusMessage = "Profile photo updated successfully"
        )
    }

    /** Lazily resolves and caches a signed or public URL for a selfie evidence path. */
    fun loadSelfieUrl(storagePath: String) {
        if (_uiState.value.selfieUrlCache.containsKey(storagePath)) return
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://")) {
            _uiState.value = _uiState.value.copy(
                selfieUrlCache = _uiState.value.selfieUrlCache + (storagePath to storagePath)
            )
            return
        }
        if (storagePath.startsWith("attendance-selfies/")) {
            val publicUrl = "${ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/public/$storagePath"
            _uiState.value = _uiState.value.copy(
                selfieUrlCache = _uiState.value.selfieUrlCache + (storagePath to publicUrl)
            )
            return
        }
        viewModelScope.launch {
            when (val result = repository.getMySelfieUrl(storagePath)) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(
                    selfieUrlCache = _uiState.value.selfieUrlCache + (storagePath to result.value)
                )
                is BackendResult.Failure -> { /* leave unresolved; UI shows a placeholder */ }
            }
        }
    }

    fun startShift(selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null, showStartShiftDialog = false)
            val location = locationHelper.getCurrentLocation()

            // The selfie is uploaded exactly once, server-side, by the `attendance` edge function from
            // the base64 payload below (the same path clock-out and the offline sync queue already use).
            // This used to also upload the same file directly to Supabase Storage from the client first —
            // a second, redundant copy of every clock-in selfie with no reader depending on it, since the
            // canonical evidence photo is always the one the edge function stores against the shift record.
            val selfieBase64 = encodeSelfie(selfieFilePath)
            val clientEventId = UUID.randomUUID().toString()
            val deviceTimestamp = Instant.now().toString()

            val result = repository.clockIn(
                clientEventId = clientEventId, deviceTimestamp = deviceTimestamp,
                latitude = location?.latitude, longitude = location?.longitude,
                accuracy = location?.accuracy, isMockLocation = location?.isMock ?: false, selfieBase64 = selfieBase64
            )
            when (result) {
                is BackendResult.Success -> {
                    val shift = result.value.shift
                    runCatching { File(selfieFilePath).delete() }
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        activeShift = shift,
                        statusMessage = "Shift started."
                    )
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueClockEvent(clientEventId, "clock_in", deviceTimestamp, location?.latitude, location?.longitude, location?.accuracy, location?.isMock ?: false, selfieFilePath)
                        runCatching { File(selfieFilePath).delete() }
                        pendingLocalClockIn = true
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            activeShift = localPendingShift(clientEventId),
                            statusMessage = "You're offline — shift queued and will sync automatically once connected."
                        )
                    } else {
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun endShift(selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null, showEndShiftDialog = false)
            val location = locationHelper.getCurrentLocation()
            val selfieBase64 = encodeSelfie(selfieFilePath)
            val clientEventId = UUID.randomUUID().toString()
            val deviceTimestamp = Instant.now().toString()

            val result = repository.clockOut(
                clientEventId = clientEventId, deviceTimestamp = deviceTimestamp,
                latitude = location?.latitude, longitude = location?.longitude,
                accuracy = location?.accuracy, isMockLocation = location?.isMock ?: false, selfieBase64 = selfieBase64
            )
            when (result) {
                is BackendResult.Success -> {
                    runCatching { File(selfieFilePath).delete() }
                    val shift = result.value.shift
                    val nowIso = Instant.now().toString()
                    val completionLog = ShiftCompletionLog(
                        logId = "LOG-${UUID.randomUUID().toString().take(8)}",
                        shiftId = shift?.id ?: clientEventId,
                        employeeId = employeeId,
                        employeeName = _uiState.value.profile?.fullName ?: "Worker",
                        projectName = shift?.project?.name ?: _uiState.value.activeShift?.project?.name ?: "Assigned Site",
                        shiftDate = shift?.shiftDate ?: OffsetDateTime.now().toLocalDate().toString(),
                        clockInTime = shift?.clockIn?.serverTimestamp ?: _uiState.value.activeShift?.clockIn?.serverTimestamp,
                        clockOutTime = shift?.clockOut?.serverTimestamp ?: nowIso,
                        totalWorkedMinutes = shift?.totalWorkedMinutes ?: 0,
                        selfieUrl = shift?.clockOut?.selfieStoragePath ?: _uiState.value.selfieUrlCache[clientEventId],
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        gpsAccuracyMeters = location?.accuracy,
                        isMockLocation = location?.isMock ?: false,
                        status = shift?.status ?: "COMPLETED",
                        supabaseSyncStatus = "STORED_IN_SUPABASE",
                        completedAtUtc = System.currentTimeMillis()
                    )
                    offlineCache.recordShiftCompletionLog(employeeId, completionLog)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false, activeShift = null,
                        completionLogs = listOf(completionLog) + _uiState.value.completionLogs,
                        statusMessage = "Shift completed and logged to Supabase database."
                    )
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueClockEvent(clientEventId, "clock_out", deviceTimestamp, location?.latitude, location?.longitude, location?.accuracy, location?.isMock ?: false, selfieFilePath)
                        runCatching { File(selfieFilePath).delete() }
                        val completionLog = ShiftCompletionLog(
                            logId = "LOG-${UUID.randomUUID().toString().take(8)}",
                            shiftId = clientEventId,
                            employeeId = employeeId,
                            employeeName = _uiState.value.profile?.fullName ?: "Worker",
                            projectName = _uiState.value.activeShift?.project?.name ?: "Assigned Site",
                            shiftDate = _uiState.value.activeShift?.shiftDate ?: OffsetDateTime.now().toLocalDate().toString(),
                            clockInTime = _uiState.value.activeShift?.clockIn?.serverTimestamp,
                            clockOutTime = deviceTimestamp,
                            totalWorkedMinutes = 0,
                            latitude = location?.latitude,
                            longitude = location?.longitude,
                            gpsAccuracyMeters = location?.accuracy,
                            isMockLocation = location?.isMock ?: false,
                            status = "QUEUED_OFFLINE",
                            supabaseSyncStatus = "QUEUED_OFFLINE",
                            completedAtUtc = System.currentTimeMillis()
                        )
                        offlineCache.recordShiftCompletionLog(employeeId, completionLog)
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false, activeShift = null,
                            completionLogs = listOf(completionLog) + _uiState.value.completionLogs,
                            statusMessage = "You're offline — shift completion queued and will sync to Supabase automatically."
                        )
                    } else {
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun submitLeave(leaveType: String, startDate: String, endDate: String, reason: String) {
        if (_uiState.value.isProcessing) return
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a reason for the leave request.")
            return
        }
        if (startDate.isBlank() || endDate.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please specify valid start and end dates.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val clientRequestId = UUID.randomUUID().toString()
            when (val result = repository.submitLeave(clientRequestId, leaveType, startDate, endDate, reason)) {
                is BackendResult.Success -> {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Leave request submitted.")
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueLeaveRequest(clientRequestId, leaveType, startDate, endDate, reason)
                        _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "You're offline — leave request queued and will submit automatically once connected.")
                    } else {
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    private fun localPendingShift(clientEventId: String): AttendanceShiftDto = AttendanceShiftDto(
        id = LOCAL_PENDING_SHIFT_PREFIX + clientEventId,
        employeeId = "", projectId = "", shiftDate = Instant.now().toString().take(10),
        clockInEventId = clientEventId, status = "OPEN", complianceFlag = "COMPLIANT",
        clockIn = AttendanceEventSummary(serverTimestamp = null, geofenceStatus = null, distanceFromProjectMeters = null)
    )

    private suspend fun encodeSelfie(filePath: String): String? {
        return com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(filePath)
    }
}
