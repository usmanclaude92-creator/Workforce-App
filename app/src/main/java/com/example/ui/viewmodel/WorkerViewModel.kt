package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.entity.*
import com.example.data.repository.WorkforceRepository
import com.example.location.DeviceLocationSnapshot
import com.example.model.LeaveType
import com.example.model.VerificationStatus
import com.example.server.LocationUtils
import com.example.server.ServerAuthorityEngine
import com.example.server.ServerGeofenceResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LocationTestScenario(val displayName: String) {
    LIVE_DEVICE_GPS("Live Android GPS"),
    INSIDE_GEOFENCE("Simulate: Inside Site Center (18m)"),
    OUTSIDE_GEOFENCE("Simulate: Outside Geofence (320m)"),
    LOW_ACCURACY("Simulate: Low GPS Accuracy (140m error)"),
    MOCK_SPOOFED("Simulate: Mock Location Spoofing")
}

data class WorkerUiState(
    val currentUser: UserEntity? = null,
    val assignedProject: ProjectEntity? = null,
    val allProjects: List<ProjectEntity> = emptyList(),
    val activeShift: AttendanceEntity? = null,
    val shiftDurationFormatted: String = "00:00:00",
    val attendanceHistory: List<AttendanceEntity> = emptyList(),
    val leaveHistory: List<LeaveRequestEntity> = emptyList(),
    val notifications: List<NotificationEntity> = emptyList(),
    // Location & Geofence State
    val testScenario: LocationTestScenario = LocationTestScenario.INSIDE_GEOFENCE,
    val currentLatitude: Double = 23.5881,
    val currentLongitude: Double = 58.3830,
    val currentAccuracy: Float = 12.0f,
    val isMockLocation: Boolean = false,
    val currentGeofenceResult: ServerGeofenceResult? = null,
    // Firestore Persistence & Sync State
    val isNetworkOnline: Boolean = true,
    val isSyncingFirestore: Boolean = false,
    val queuedFirestoreCount: Int = 0,
    val lastFirestoreSyncTime: Long? = null,
    val firestoreSyncLogs: List<com.example.sync.SyncLogItem> = emptyList(),
    // Operations & UI Feedback
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastAttendanceResult: AttendanceEntity? = null,
    val showStartShiftDialog: Boolean = false,
    val showEndShiftDialog: Boolean = false,
    val showLeaveDialog: Boolean = false,
    val showProfileCameraDialog: Boolean = false
)

class WorkerViewModel(
    private val repository: WorkforceRepository,
    private val user: UserEntity
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkerUiState(currentUser = user))
    val uiState: StateFlow<WorkerUiState> = _uiState.asStateFlow()

    init {
        loadData()
        startLiveShiftTimer()
    }

    private fun loadData() {
        viewModelScope.launch {
            // Load assigned project
            val project = repository.getProjectById(user.assignedProjectId)
            _uiState.value = _uiState.value.copy(assignedProject = project)
            updateLocationScenario(LocationTestScenario.INSIDE_GEOFENCE)

            // Check active shift
            val active = repository.getActiveShift(user.employeeId)
            _uiState.value = _uiState.value.copy(activeShift = active)
        }

        viewModelScope.launch {
            repository.getAllProjects().collect { projects ->
                _uiState.value = _uiState.value.copy(allProjects = projects)
            }
        }

        viewModelScope.launch {
            repository.getAttendanceForEmployee(user.employeeId).collect { history ->
                val active = history.firstOrNull { it.endTimeUtc == null && it.state != "CANCELLED" }
                _uiState.value = _uiState.value.copy(
                    attendanceHistory = history,
                    activeShift = active
                )
            }
        }

        viewModelScope.launch {
            repository.getLeaveRequestsForEmployee(user.employeeId).collect { leaves ->
                _uiState.value = _uiState.value.copy(leaveHistory = leaves)
            }
        }

        viewModelScope.launch {
            repository.getNotifications(user.employeeId).collect { notifs ->
                _uiState.value = _uiState.value.copy(notifications = notifs)
            }
        }

        // Observe Firestore Offline Persistence & Sync
        val syncMgr = repository.firestoreSyncManager
        if (syncMgr != null) {
            viewModelScope.launch {
                syncMgr.isOnline.collect { online ->
                    _uiState.value = _uiState.value.copy(isNetworkOnline = online)
                }
            }
            viewModelScope.launch {
                syncMgr.isSyncing.collect { syncing ->
                    _uiState.value = _uiState.value.copy(isSyncingFirestore = syncing)
                }
            }
            viewModelScope.launch {
                syncMgr.queuedCount.collect { count ->
                    _uiState.value = _uiState.value.copy(queuedFirestoreCount = count)
                }
            }
            viewModelScope.launch {
                syncMgr.lastSyncTimestampUtc.collect { lastSync ->
                    _uiState.value = _uiState.value.copy(lastFirestoreSyncTime = lastSync)
                }
            }
            viewModelScope.launch {
                syncMgr.syncLogs.collect { logs ->
                    _uiState.value = _uiState.value.copy(firestoreSyncLogs = logs)
                }
            }
        }
    }

    fun triggerManualSync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(statusMessage = "Synchronizing offline queue with Firestore...")
            val count = repository.syncPendingClockIns()
            if (count > 0) {
                _uiState.value = _uiState.value.copy(statusMessage = "Successfully synchronized $count clock-in record(s) with Firestore Cloud.")
            } else {
                _uiState.value = _uiState.value.copy(statusMessage = "All clock-in records are up to date.")
            }
        }
    }

    fun simulateNetwork(enableOnline: Boolean) {
        repository.simulateFirestoreNetwork(enableOnline)
    }

    private fun startLiveShiftTimer() {
        viewModelScope.launch {
            while (isActive) {
                val active = _uiState.value.activeShift
                if (active != null && active.startTimeUtc != null) {
                    val now = System.currentTimeMillis()
                    val diffSec = ((now - active.startTimeUtc) / 1000).coerceAtLeast(0)
                    val hours = diffSec / 3600
                    val mins = (diffSec % 3600) / 60
                    val secs = diffSec % 60
                    val formatted = String.format("%02d:%02d:%02d", hours, mins, secs)
                    _uiState.value = _uiState.value.copy(shiftDurationFormatted = formatted)
                } else {
                    _uiState.value = _uiState.value.copy(shiftDurationFormatted = "00:00:00")
                }
                delay(1000L)
            }
        }
    }

    fun updateLocationScenario(scenario: LocationTestScenario) {
        val proj = _uiState.value.assignedProject
        val (lat, lng, acc, isMock) = when (scenario) {
            LocationTestScenario.INSIDE_GEOFENCE -> {
                val pLat = proj?.latitude ?: 23.5880
                val pLng = proj?.longitude ?: 58.3829
                // ~15m from center
                Tuple4(pLat + 0.0001, pLng + 0.0001, 12.0f, false)
            }
            LocationTestScenario.OUTSIDE_GEOFENCE -> {
                val pLat = proj?.latitude ?: 23.5880
                val pLng = proj?.longitude ?: 58.3829
                // ~320m away
                Tuple4(pLat + 0.0030, pLng + 0.0030, 15.0f, false)
            }
            LocationTestScenario.LOW_ACCURACY -> {
                val pLat = proj?.latitude ?: 23.5880
                val pLng = proj?.longitude ?: 58.3829
                Tuple4(pLat + 0.0001, pLng + 0.0001, 145.0f, false)
            }
            LocationTestScenario.MOCK_SPOOFED -> {
                val pLat = proj?.latitude ?: 23.5880
                val pLng = proj?.longitude ?: 58.3829
                Tuple4(pLat, pLng, 10.0f, true)
            }
            LocationTestScenario.LIVE_DEVICE_GPS -> {
                Tuple4(_uiState.value.currentLatitude, _uiState.value.currentLongitude, _uiState.value.currentAccuracy, false)
            }
        }

        val geofenceResult = proj?.let {
            ServerAuthorityEngine.evaluateGeofence(
                latitude = lat,
                longitude = lng,
                accuracyMeters = acc,
                isMockLocation = isMock,
                project = it
            )
        }

        _uiState.value = _uiState.value.copy(
            testScenario = scenario,
            currentLatitude = lat,
            currentLongitude = lng,
            currentAccuracy = acc,
            isMockLocation = isMock,
            currentGeofenceResult = geofenceResult
        )
    }

    fun onDeviceLocationReceived(snapshot: DeviceLocationSnapshot) {
        val proj = _uiState.value.assignedProject
        val geofenceResult = proj?.let {
            ServerAuthorityEngine.evaluateGeofence(
                latitude = snapshot.latitude,
                longitude = snapshot.longitude,
                accuracyMeters = snapshot.accuracy,
                isMockLocation = snapshot.isMock,
                project = it
            )
        }
        _uiState.value = _uiState.value.copy(
            currentLatitude = snapshot.latitude,
            currentLongitude = snapshot.longitude,
            currentAccuracy = snapshot.accuracy,
            isMockLocation = snapshot.isMock,
            currentGeofenceResult = geofenceResult
        )
    }

    fun startShift(selfieData: String) {
        if (_uiState.value.isProcessing) return
        val project = _uiState.value.assignedProject
        if (project == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "No active assigned project found.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val result = repository.startShift(
                employee = user,
                project = project,
                latitude = _uiState.value.currentLatitude,
                longitude = _uiState.value.currentLongitude,
                accuracy = _uiState.value.currentAccuracy,
                isMockLocation = _uiState.value.isMockLocation,
                selfieData = selfieData,
                deviceId = "ANDROID-ARTIFY-101"
            )

            result.onSuccess { attendance ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    activeShift = attendance,
                    lastAttendanceResult = attendance,
                    statusMessage = "Shift successfully started! Server timestamp: ${attendance.startTimeFormatted}",
                    showStartShiftDialog = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = error.message ?: "Failed to start shift."
                )
            }
        }
    }

    fun endShift(selfieData: String? = null) {
        if (_uiState.value.isProcessing) return
        val project = _uiState.value.assignedProject
        if (project == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "No assigned project found.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val result = repository.endShift(
                employee = user,
                project = project,
                latitude = _uiState.value.currentLatitude,
                longitude = _uiState.value.currentLongitude,
                accuracy = _uiState.value.currentAccuracy,
                isMockLocation = _uiState.value.isMockLocation,
                selfieData = selfieData,
                deviceId = "ANDROID-ARTIFY-101"
            )

            result.onSuccess { attendance ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    activeShift = null,
                    lastAttendanceResult = attendance,
                    statusMessage = "Shift completed! Total worked time: ${attendance.totalWorkedMinutes} mins. Submitted for supervisor approval.",
                    showEndShiftDialog = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = error.message ?: "Failed to end shift."
                )
            }
        }
    }

    fun submitLeave(
        type: LeaveType,
        startDate: String,
        endDate: String,
        totalDays: Int,
        reason: String
    ) {
        if (_uiState.value.isProcessing) return
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a reason for the leave request.")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val result = repository.submitLeaveRequest(
                employee = user,
                type = type,
                startDate = startDate,
                endDate = endDate,
                totalDays = totalDays,
                reason = reason
            )

            result.onSuccess {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    statusMessage = "Leave request submitted successfully.",
                    showLeaveDialog = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = error.message ?: "Failed to submit leave request."
                )
            }
        }
    }

    fun markNotificationRead(id: String) {
        viewModelScope.launch {
            repository.markNotificationAsRead(id)
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }

    // Geofence/GPS/mock-location status is never a gate on attendance, and is never
    // surfaced to the worker in advance — it is captured on the record silently and
    // is visible only to supervisors/HCM for review. The selfie capture dialog always
    // opens immediately, regardless of location.
    fun setStartShiftDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showStartShiftDialog = show)
    }

    fun refreshLiveLocation(locationHelper: com.example.location.LocationHelper) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)
            val snapshot = locationHelper.getCurrentLocation()
            if (snapshot != null) {
                onDeviceLocationReceived(snapshot)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    testScenario = LocationTestScenario.LIVE_DEVICE_GPS,
                    statusMessage = "Location updated via Google Location Services."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = "Unable to fetch current location. Ensure GPS and permissions are enabled."
                )
            }
        }
    }

    fun setEndShiftDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showEndShiftDialog = show)
    }

    fun setLeaveDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showLeaveDialog = show)
    }

    fun setProfileCameraDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showProfileCameraDialog = show)
    }

    fun updateProfilePicture(filePath: String) {
        val currentUser = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, showProfileCameraDialog = false)
            val result = repository.updateUserProfilePicture(currentUser.employeeId, filePath)
            result.onSuccess { updatedUser ->
                _uiState.value = _uiState.value.copy(
                    currentUser = updatedUser,
                    isProcessing = false,
                    statusMessage = "Profile picture updated successfully!"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = "Failed to update profile picture: ${err.message}"
                )
            }
        }
    }
}

data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
