package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackendResult
import com.example.data.repository.BackendWorkforceRepository
import com.example.network.ArtifyBackendConfig
import com.example.network.AttendanceShiftDto
import com.example.network.AuditLogDto
import com.example.network.ErpEventDto
import com.example.network.LeaveRequestDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto
import com.example.notifications.FcmNotificationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RealSupervisorUiState(
    val pendingAttendance: List<AttendanceShiftDto> = emptyList(),
    val pendingLeave: List<LeaveRequestDto> = emptyList(),
    val attendanceRoster: List<AttendanceShiftDto> = emptyList(),
    val sites: List<SiteDto> = emptyList(),
    val auditLogs: List<AuditLogDto> = emptyList(),
    val erpEvents: List<ErpEventDto> = emptyList(),
    val metrics: SupervisorMetricsDto = SupervisorMetricsDto(),
    val isLoading: Boolean = true,
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val selfieUrlCache: Map<String, String> = emptyMap()
)

class RealSupervisorViewModel(private val repository: BackendWorkforceRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RealSupervisorUiState())
    val uiState: StateFlow<RealSupervisorUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = repository.pendingAttendance()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(pendingAttendance = result.value)
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
            }
            when (val result = repository.pendingLeave()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(pendingLeave = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.attendanceRoster()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(attendanceRoster = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.sites()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(sites = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.auditLog()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(auditLogs = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.erpOutbox()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(erpEvents = result.value)
                is BackendResult.Failure -> {}
            }
            when (val result = repository.supervisorMetrics()) {
                is BackendResult.Success -> _uiState.value = _uiState.value.copy(metrics = result.value)
                is BackendResult.Failure -> {}
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun clearFeedback() { _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null) }

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

    fun reviewAttendance(shiftId: String, approve: Boolean, comment: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            when (val result = repository.reviewAttendance(shiftId, approve, comment)) {
                is BackendResult.Success -> {
                    _uiState.value = _uiState.value.copy(isProcessing = false, statusMessage = if (approve) "Attendance approved." else "Attendance rejected.")
                    refresh()
                }
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
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
                is BackendResult.Failure -> _uiState.value = _uiState.value.copy(isProcessing = false, errorMessage = result.message)
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
                    statusMessage = "✓ $actionLabel push alert dispatched to $employeeName via Firebase Cloud Messaging."
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
