package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackendResult
import com.example.data.repository.BackendWorkforceRepository
import com.example.data.sync.OfflineCache
import com.example.data.sync.RealSyncManager
import com.example.data.sync.SyncQueueStatus
import com.example.location.LocationHelper
import com.example.network.AttendanceEventSummary
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NotificationDto
import com.example.network.ProfileDto
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
    private val repository: BackendWorkforceRepository,
    private val locationHelper: LocationHelper,
    private val syncManager: RealSyncManager,
    private val offlineCache: OfflineCache,
    private val employeeId: String
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
                        _uiState.value = _uiState.value.copy(activeShift = active, shiftHistory = history, isLoading = false)
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
                            _uiState.value = _uiState.value.copy(activeShift = active, shiftHistory = history, isLoading = false)
                        } else {
                            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = result.message)
                        }
                    }
                }
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
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
            if (_uiState.value.profile == null) {
                when (val result = repository.myProfile()) {
                    is BackendResult.Success -> {
                        offlineCache.cacheProfile(employeeId, result.value)
                        _uiState.value = _uiState.value.copy(profile = result.value)
                    }
                    is BackendResult.Failure -> {
                        if (result.isNetworkError) offlineCache.getCachedProfile(employeeId)?.let { _uiState.value = _uiState.value.copy(profile = it) }
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

    /** Lazily resolves and caches a signed URL for a selfie evidence path. */
    fun loadSelfieUrl(storagePath: String) {
        if (_uiState.value.selfieUrlCache.containsKey(storagePath)) return
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
                    runCatching { File(selfieFilePath).delete() }
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false, activeShift = result.value.shift,
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
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false, activeShift = null,
                        statusMessage = "Shift ended — submitted for supervisor approval."
                    )
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueClockEvent(clientEventId, "clock_out", deviceTimestamp, location?.latitude, location?.longitude, location?.accuracy, location?.isMock ?: false, selfieFilePath)
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false, activeShift = null,
                            statusMessage = "You're offline — clock-out queued and will sync automatically once connected."
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
