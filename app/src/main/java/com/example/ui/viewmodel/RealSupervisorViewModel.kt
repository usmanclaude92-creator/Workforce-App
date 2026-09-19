package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackendResult
import com.example.data.repository.IWorkforceRepository
import com.example.data.sync.OfflineCache
import com.example.data.sync.RealSyncManager
import com.example.data.sync.SupervisorActionType
import com.example.data.sync.SyncQueueStatus
import com.example.location.LocationHelper
import com.example.network.ArtifyBackendConfig
import com.example.network.AttendanceApprovalDto
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NoMobileWorkerDto
import com.example.network.ProfileDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto
import com.example.notifications.FcmNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID

data class RealSupervisorUiState(
    val pendingAttendance: List<AttendanceShiftDto> = emptyList(),
    val pendingAttendanceApprovals: List<AttendanceApprovalDto> = emptyList(),
    val pendingLeave: List<LeaveRequestDto> = emptyList(),
    val attendanceRoster: List<AttendanceShiftDto> = emptyList(),
    val sites: List<SiteDto> = emptyList(),
    val metrics: SupervisorMetricsDto = SupervisorMetricsDto(),
    // Supervisor's own current shift (from myShifts -- the same endpoint the Worker
    // dashboard uses), and every employee on the same project under this supervisor
    // (used as the "whose attendance is this?" picklist after a selfie capture).
    val myShift: AttendanceShiftDto? = null,
    val teamRoster: List<NoMobileWorkerDto> = emptyList(),
    val profile: ProfileDto? = null,
    val localAvatarPath: String? = null,
    // Home tab attendance flow: tap the single Start/End Shift with Selfie button ->
    // capture a photo (CameraXSelfieDialog, same component the Worker app uses) -> THEN
    // pick who it's for (defaults to the supervisor, or any project employee) -> punch.
    val showAttendanceCameraDialog: Boolean = false,
    val isLoading: Boolean = true,
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val selfieUrlCache: Map<String, String> = emptyMap(),
    val syncQueue: SyncQueueStatus = SyncQueueStatus()
)

class RealSupervisorViewModel(
    private val repository: IWorkforceRepository,
    private val locationHelper: LocationHelper,
    private val syncManager: RealSyncManager,
    private val offlineCache: OfflineCache,
    private val supervisorId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(RealSupervisorUiState())
    val uiState: StateFlow<RealSupervisorUiState> = _uiState.asStateFlow()

    init {
        syncManager.start(viewModelScope)
        viewModelScope.launch {
            syncManager.status.collect { status ->
                _uiState.value = _uiState.value.copy(syncQueue = status)
            }
        }
        viewModelScope.launch { syncManager.refreshCounts() }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = repository.pendingAttendance()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(pendingAttendance = result.value)
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
            when (val result = repository.pendingAttendanceApprovals()) {
                is BackendResult.Success -> {
                    offlineCache.cachePendingAttendanceApprovals(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(pendingAttendanceApprovals = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedPendingAttendanceApprovals(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(pendingAttendanceApprovals = it)
                    }
                }
            }
            when (val result = repository.pendingLeave()) {
                is BackendResult.Success -> {
                    offlineCache.cachePendingLeave(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(pendingLeave = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedPendingLeave(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(pendingLeave = it)
                    }
                }
            }
            when (val result = repository.attendanceRoster()) {
                is BackendResult.Success -> {
                    offlineCache.cacheAttendanceRoster(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(attendanceRoster = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedAttendanceRoster(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(attendanceRoster = it)
                    }
                }
            }
            when (val result = repository.sites()) {
                is BackendResult.Success -> {
                    offlineCache.cacheSites(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(sites = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedSites(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(sites = it)
                    }
                }
            }
            when (val result = repository.supervisorMetrics()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(metrics = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.myShifts()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(myShift = result.value.firstOrNull())
                is BackendResult.Failure -> {}
            }
            when (val result = repository.teamRoster()) {
                is BackendResult.Success -> {
                    offlineCache.cacheTeamRoster(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(teamRoster = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedTeamRoster(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(teamRoster = it)
                    }
                }
            }
            when (val result = repository.myProfile()) {
                is BackendResult.Success -> {
                    offlineCache.cacheProfile(supervisorId, result.value)
                    _uiState.value = _uiState.value.copy(profile = result.value)
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) offlineCache.getCachedProfile(supervisorId)?.let {
                        _uiState.value = _uiState.value.copy(profile = it)
                    }
                }
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun clearFeedback() { _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null) }

    fun setAttendanceCameraDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showAttendanceCameraDialog = show) }

    fun updateProfilePhoto(filePath: String) {
        _uiState.value = _uiState.value.copy(
            localAvatarPath = filePath,
            statusMessage = "Profile photo updated successfully"
        )
    }

    // -------------------- Supervisor's own attendance --------------------
    // Reuses the same `attendance` clock_in/clock_out actions the Worker dashboard uses --
    // a supervisor is also just an employee with their own device session. The selfie is
    // captured via the same CameraXSelfieDialog the Worker app uses (see
    // RealSupervisorDashboardScreen) BEFORE the target (self or a team member) is chosen,
    // and location is fetched here, right before the punch, exactly as
    // RealWorkerViewModel.startShift/endShift already do.

    fun clockInSelf(selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val location = locationHelper.getCurrentLocation()
            val selfieBase64 = com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(selfieFilePath)
            val result = repository.clockIn(
                clientEventId = UUID.randomUUID().toString(),
                deviceTimestamp = Instant.now().toString(),
                latitude = location?.latitude, longitude = location?.longitude,
                accuracy = location?.accuracy, isMockLocation = location?.isMock ?: false, selfieBase64 = selfieBase64
            )
            runCatching { File(selfieFilePath).delete() }
            when (result) {
                is BackendResult.Success -> {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Clocked in.", myShift = result.value.shift)
                }
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
            }
        }
    }

    fun clockOutSelf(selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val location = locationHelper.getCurrentLocation()
            val selfieBase64 = com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(selfieFilePath)
            val result = repository.clockOut(
                clientEventId = UUID.randomUUID().toString(),
                deviceTimestamp = Instant.now().toString(),
                latitude = location?.latitude, longitude = location?.longitude,
                accuracy = location?.accuracy, isMockLocation = location?.isMock ?: false, selfieBase64 = selfieBase64
            )
            runCatching { File(selfieFilePath).delete() }
            when (result) {
                is BackendResult.Success -> {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Clocked out.", myShift = result.value.shift)
                }
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
            }
        }
    }

    // -------------------- Proxy attendance for a team member --------------------
    // Same selfie-capture flow as above, on behalf of any employee on the same project
    // the supervisor chose in the post-capture "whose attendance is this?" picker -- the
    // resulting evidence photo is uploaded server-side exactly like a self-service punch's.

    fun proxyClockIn(employeeId: String, selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val location = locationHelper.getCurrentLocation()
            val selfieBase64 = com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(selfieFilePath)
            val result = repository.proxyClockIn(employeeId, location?.latitude, location?.longitude, selfieBase64)
            when (result) {
                is BackendResult.Success -> {
                    runCatching { File(selfieFilePath).delete() }
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Clock-in recorded.")
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueProxyClockEvent(SupervisorActionType.PROXY_CLOCK_IN, employeeId, location?.latitude, location?.longitude, selfieFilePath)
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Offline — clock-in queued and will sync automatically.")
                    } else {
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun proxyClockOut(employeeId: String, selfieFilePath: String) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val location = locationHelper.getCurrentLocation()
            val selfieBase64 = com.example.util.ImageCompressionUtils.compressAndEncodeSelfie(selfieFilePath)
            val result = repository.proxyClockOut(employeeId, location?.latitude, location?.longitude, selfieBase64)
            when (result) {
                is BackendResult.Success -> {
                    runCatching { File(selfieFilePath).delete() }
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Clock-out recorded.")
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueProxyClockEvent(SupervisorActionType.PROXY_CLOCK_OUT, employeeId, location?.latitude, location?.longitude, selfieFilePath)
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = "Offline — clock-out queued and will sync automatically.")
                    } else {
                        runCatching { File(selfieFilePath).delete() }
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

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
            when (val result = repository.getTeamSelfieUrl(storagePath)) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(
                    selfieUrlCache = _uiState.value.selfieUrlCache + (storagePath to result.value)
                )
                is BackendResult.Failure -> {}
            }
        }
    }

    fun reviewAttendance(shiftId: String, approve: Boolean, comment: String?, context: Context? = null, supervisorName: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            when (val result = repository.reviewAttendance(shiftId, approve, comment)) {
                is BackendResult.Success -> {
                    val shift = result.value
                    if (context != null) {
                        runCatching {
                            FcmNotificationManager.dispatchAttendanceApprovalPushNotification(
                                context = context,
                                shiftId = shift.id,
                                employeeId = shift.employeeId,
                                employeeName = shift.employee?.fullName ?: "Employee",
                                shiftDate = shift.shiftDate,
                                supervisorName = supervisorName ?: "Supervisor",
                                isApproved = approve,
                                commentOrReason = comment
                            )
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = if (approve) "Attendance approved." else "Attendance rejected."
                    )
                    refresh()
                }
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
            }
        }
    }

    /**
     * Loads pending attendance requests specifically querying the 'attendance_approvals' table.
     */
    fun loadPendingAttendanceApprovals() {
        viewModelScope.launch {
            when (val result = repository.pendingAttendanceApprovals()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(pendingAttendanceApprovals = result.value)
                is BackendResult.Failure -> {}
            }
        }
    }

    /**
     * Updates the status of an attendance record in the 'attendance_approvals' table based on a supervisor's 'Approve' or 'Reject' action,
     * and dispatches a Firebase Cloud Messaging notification to the employee.
     */
    fun updateAttendanceApproval(
        shiftId: String,
        approve: Boolean,
        comment: String?,
        context: Context? = null,
        supervisorName: String? = null
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val decision = if (approve) "APPROVED" else "REJECTED"
            when (val result = repository.updateAttendanceApprovalStatus(shiftId, decision, comment)) {
                is BackendResult.Success -> {
                    val approval = result.value
                    if (context != null) {
                        runCatching {
                            FcmNotificationManager.dispatchAttendanceApprovalPushNotification(
                                context = context,
                                shiftId = approval.shiftId,
                                employeeId = approval.employeeId,
                                employeeName = approval.employeeName ?: "Employee",
                                shiftDate = approval.shiftDate ?: "Today",
                                supervisorName = supervisorName ?: "Supervisor",
                                isApproved = approve,
                                commentOrReason = comment
                            )
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        statusMessage = if (approve) "Attendance record approved." else "Attendance record rejected."
                    )
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueAttendanceApproval(shiftId, approve, comment)
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            statusMessage = "Offline — ${if (approve) "approval" else "rejection"} queued and will sync automatically."
                        )
                    } else {
                        // Fallback to reviewAttendance if direct approval endpoint reported a non-network rejection
                        reviewAttendance(shiftId, approve, comment, context, supervisorName)
                    }
                }
            }
        }
    }

    fun reviewLeave(leaveId: String, approve: Boolean, comment: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            when (val result = repository.reviewLeave(leaveId, approve, comment)) {
                is BackendResult.Success -> {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = if (approve) "Leave approved." else "Leave rejected.")
                    refresh()
                }
                is BackendResult.Failure -> {
                    if (result.isNetworkError) {
                        syncManager.queueLeaveReview(leaveId, approve, comment)
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            statusMessage = "Offline — leave ${if (approve) "approval" else "rejection"} queued and will sync automatically."
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun assignShiftAndNotifyEmployee(
        context: Context,
        supervisorName: String,
        employeeId: String,
        employeeName: String,
        projectName: String,
        shiftDate: String,
        shiftTiming: String,
        notes: String?,
        isScheduleChange: Boolean = false,
        changeReason: String? = null,
        oldTiming: String? = null
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            try {
                if (isScheduleChange) {
                    FcmNotificationManager.dispatchScheduleChangeAlert(
                        context = context,
                        employeeId = employeeId,
                        employeeName = employeeName,
                        projectName = projectName,
                        shiftDate = shiftDate,
                        newTiming = shiftTiming,
                        oldTiming = oldTiming,
                        supervisorName = supervisorName,
                        changeReason = changeReason
                    )
                } else {
                    FcmNotificationManager.dispatchShiftAssignmentAlert(
                        context = context,
                        employeeId = employeeId,
                        employeeName = employeeName,
                        projectName = projectName,
                        shiftDate = shiftDate,
                        shiftTiming = shiftTiming,
                        supervisorName = supervisorName,
                        notes = notes
                    )
                }
                val actionLabel = if (isScheduleChange) "Schedule update" else "Shift assignment"
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "✓ $actionLabel successfully sent to $employeeName."
                )
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = "Error sending push alert: ${e.message}"
                )
            }
        }
    }
}
