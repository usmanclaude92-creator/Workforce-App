package com.example.data.repository

import android.content.Context
import android.location.Location
import com.example.data.AppDatabase
import com.example.data.entity.AttendanceEntity
import com.example.data.entity.AuditLogEntity
import com.example.data.entity.LeaveRequestEntity
import com.example.network.AttendanceEventResponse
import com.example.network.AttendanceEventSummary
import com.example.network.AttendanceShiftDto
import com.example.network.AttendanceVerificationEntry
import com.example.network.AttendanceVerificationResponse
import com.example.network.AuditLogDto
import com.example.network.EmployeeSummary
import com.example.network.ErpEventDto
import com.example.network.FacialMetadataDto
import com.example.network.LeaveRequestDto
import com.example.network.NotificationDto
import com.example.network.ProfileDto
import com.example.network.ProjectSummary
import com.example.network.RosterEmployeeDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Isolated, offline-capable Demo Data Provider.
 * Implements [IWorkforceRepository] completely locally using Room Database.
 * Never touches real HCMS, Supabase, or production cloud backends.
 */
class DemoWorkforceRepository(
    private val database: AppDatabase,
    private val currentEmployeeId: String,
    private val context: Context
) : IWorkforceRepository {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val shiftDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

    override suspend fun clockIn(
        clientEventId: String,
        deviceTimestamp: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieBase64: String?
    ): BackendResult<AttendanceEventResponse> {
        val now = System.currentTimeMillis()
        val user = database.userDao().getUserByEmployeeId(currentEmployeeId)
        val project = database.projectDao().getAllProjects().first().firstOrNull()

        // Calculate distance and geofence status
        var distanceMeters = 25.0
        var geofenceStatus = "INSIDE"
        if (latitude != null && longitude != null && project != null) {
            val results = FloatArray(1)
            Location.distanceBetween(latitude, longitude, project.latitude, project.longitude, results)
            distanceMeters = results[0].toDouble()
            geofenceStatus = if (distanceMeters <= project.geofenceRadiusMeters) "INSIDE" else "OUTSIDE"
        }

        val attendanceId = "ATT-DEMO-" + UUID.randomUUID().toString().take(8).uppercase()
        val todayStr = shiftDateFormat.format(Date(now))
        val timeStr = timeFormat.format(Date(now))

        val entity = AttendanceEntity(
            attendanceId = attendanceId,
            employeeId = currentEmployeeId,
            employeeName = user?.fullName ?: "Demo Worker",
            employeeRole = user?.role ?: "WORKER",
            projectId = project?.projectId ?: "PRJ-001",
            projectName = project?.projectName ?: "Muscat Commercial Bay Tower",
            shiftDate = todayStr,
            startTimeUtc = now,
            startTimeFormatted = timeStr,
            endTimeUtc = null,
            endTimeFormatted = null,
            totalWorkedMinutes = 0,
            startSelfieData = selfieBase64?.take(100) ?: "mock_selfie_in.jpg",
            startLatitude = latitude ?: 23.5880,
            startLongitude = longitude ?: 58.3829,
            startAccuracy = accuracy ?: 8.5f,
            startGeofenceStatus = geofenceStatus,
            startDistanceFromProjectMeters = distanceMeters,
            isStartMockLocation = isMockLocation,
            state = "OPEN",
            verificationStatus = "VERIFIED",
            deviceId = "ANDROID-DEMO-" + currentEmployeeId.takeLast(4),
            appVersion = "1.0.0-demo",
            createdAtUtc = now
        )

        database.attendanceDao().insertAttendance(entity)

        // Log audit
        database.auditDao().insertAuditLog(
            AuditLogEntity(
                auditId = UUID.randomUUID().toString(),
                actorId = currentEmployeeId,
                actorName = user?.fullName ?: "Demo Worker",
                actorRole = user?.role ?: "WORKER",
                action = "CLOCK_IN",
                entityType = "ATTENDANCE",
                entityId = attendanceId,
                serverTimestampUtc = now,
                details = "Clock-in at $geofenceStatus geofence (${distanceMeters.toInt()}m)"
            )
        )

        val shiftDto = entity.toShiftDto(displayDateFormat, isoFormat)
        return BackendResult.Success(
            AttendanceEventResponse(
                shift = shiftDto,
                serverTimestamp = isoFormat.format(Date(now)),
                geofenceStatus = geofenceStatus,
                distanceMeters = distanceMeters
            )
        )
    }

    override suspend fun clockOut(
        clientEventId: String,
        deviceTimestamp: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieBase64: String?
    ): BackendResult<AttendanceEventResponse> {
        val now = System.currentTimeMillis()
        val active = database.attendanceDao().getAnyActiveShift(currentEmployeeId)
            ?: return BackendResult.Failure("No active clock-in session found to clock out.")

        val project = database.projectDao().getProjectById(active.projectId)
        var distanceMeters = 30.0
        var geofenceStatus = "INSIDE"
        if (latitude != null && longitude != null && project != null) {
            val results = FloatArray(1)
            Location.distanceBetween(latitude, longitude, project.latitude, project.longitude, results)
            distanceMeters = results[0].toDouble()
            geofenceStatus = if (distanceMeters <= project.geofenceRadiusMeters) "INSIDE" else "OUTSIDE"
        }

        val startMs = active.startTimeUtc ?: now
        val workedMinutes = ((now - startMs) / 60000L).toInt().coerceAtLeast(1)

        val updated = active.copy(
            endTimeUtc = now,
            endTimeFormatted = timeFormat.format(Date(now)),
            totalWorkedMinutes = workedMinutes,
            endSelfieData = selfieBase64?.take(100) ?: "mock_selfie_out.jpg",
            endLatitude = latitude ?: 23.5880,
            endLongitude = longitude ?: 58.3829,
            endAccuracy = accuracy ?: 9.0f,
            endGeofenceStatus = geofenceStatus,
            endDistanceFromProjectMeters = distanceMeters,
            isEndMockLocation = isMockLocation,
            state = "PENDING_APPROVAL"
        )
        database.attendanceDao().updateAttendance(updated)

        database.auditDao().insertAuditLog(
            AuditLogEntity(
                auditId = UUID.randomUUID().toString(),
                actorId = currentEmployeeId,
                actorName = active.employeeName,
                actorRole = active.employeeRole,
                action = "CLOCK_OUT",
                entityType = "ATTENDANCE",
                entityId = active.attendanceId,
                serverTimestampUtc = now,
                details = "Clock-out logged with $workedMinutes worked minutes"
            )
        )

        val shiftDto = updated.toShiftDto(displayDateFormat, isoFormat)
        return BackendResult.Success(
            AttendanceEventResponse(
                shift = shiftDto,
                serverTimestamp = isoFormat.format(Date(now)),
                geofenceStatus = geofenceStatus,
                distanceMeters = distanceMeters
            )
        )
    }

    override suspend fun recordAttendanceVerification(
        entry: AttendanceVerificationEntry
    ): BackendResult<AttendanceVerificationResponse> {
        val now = isoFormat.format(Date())
        return BackendResult.Success(
            AttendanceVerificationResponse(
                success = true,
                message = "Demo verification recorded locally",
                entry = entry,
                serverTimestamp = now
            )
        )
    }

    override suspend fun myShifts(): BackendResult<List<AttendanceShiftDto>> {
        val list = database.attendanceDao().getAttendanceForEmployee(currentEmployeeId).first()
        // Last 30 attendances in latest to oldest order
        val dtos = list.sortedByDescending { it.startTimeUtc ?: it.createdAtUtc }
            .take(30)
            .map { it.toShiftDto(displayDateFormat, isoFormat) }
        return BackendResult.Success(dtos)
    }

    override suspend fun submitLeave(
        clientRequestId: String,
        leaveType: String,
        startDate: String,
        endDate: String,
        reason: String
    ): BackendResult<LeaveRequestDto> {
        val now = System.currentTimeMillis()
        val user = database.userDao().getUserByEmployeeId(currentEmployeeId)
        val id = "LR-DEMO-" + UUID.randomUUID().toString().take(6).uppercase()

        val startMs = runCatching { shiftDateFormat.parse(startDate)?.time }.getOrNull() ?: now
        val endMs = runCatching { shiftDateFormat.parse(endDate)?.time }.getOrNull() ?: now
        val days = (((endMs - startMs) / 86400000L) + 1).coerceAtLeast(1).toInt()

        val entity = LeaveRequestEntity(
            requestId = id,
            employeeId = currentEmployeeId,
            employeeName = user?.fullName ?: "Demo Worker",
            employeeRole = user?.role ?: "WORKER",
            type = leaveType,
            startDate = startDate,
            endDate = endDate,
            totalDays = days,
            reason = reason,
            status = "PENDING",
            submittedAtUtc = now
        )
        database.leaveDao().insertLeaveRequest(entity)

        return BackendResult.Success(entity.toDto())
    }

    override suspend fun myLeaveRequests(): BackendResult<List<LeaveRequestDto>> {
        val list = database.leaveDao().getLeaveRequestsForEmployee(currentEmployeeId).first()
        val dtos = list.sortedByDescending { it.submittedAtUtc }.map { it.toDto() }
        return BackendResult.Success(dtos)
    }

    override suspend fun pendingAttendance(): BackendResult<List<AttendanceShiftDto>> {
        val list = database.attendanceDao().getPendingApprovals().first()
        val dtos = list.map { it.toShiftDto(displayDateFormat, isoFormat) }
        return BackendResult.Success(dtos)
    }

    override suspend fun reviewAttendance(
        shiftId: String,
        approve: Boolean,
        comment: String?
    ): BackendResult<AttendanceShiftDto> {
        val shift = database.attendanceDao().getAttendanceById(shiftId)
            ?: return BackendResult.Failure("Shift not found: $shiftId")

        val now = System.currentTimeMillis()
        val supervisor = database.userDao().getUserByEmployeeId(currentEmployeeId)

        val updated = shift.copy(
            state = if (approve) "APPROVED" else "REJECTED",
            reviewedBySupervisorId = supervisor?.fullName ?: "Supervisor",
            reviewedAtUtc = now,
            supervisorComment = comment
        )
        database.attendanceDao().updateAttendance(updated)
        return BackendResult.Success(updated.toShiftDto(displayDateFormat, isoFormat))
    }

    override suspend fun pendingLeave(): BackendResult<List<LeaveRequestDto>> {
        val list = database.leaveDao().getPendingLeaveRequests().first()
        val dtos = list.map { it.toDto() }
        return BackendResult.Success(dtos)
    }

    override suspend fun reviewLeave(
        leaveId: String,
        approve: Boolean,
        comment: String?
    ): BackendResult<LeaveRequestDto> {
        val req = database.leaveDao().getLeaveRequestById(leaveId)
            ?: return BackendResult.Failure("Leave request not found: $leaveId")

        val now = System.currentTimeMillis()
        val supervisor = database.userDao().getUserByEmployeeId(currentEmployeeId)

        val updated = req.copy(
            status = if (approve) "APPROVED" else "REJECTED",
            rejectionReason = comment,
            approvedBy = supervisor?.fullName ?: "Supervisor",
            approvedAtUtc = now
        )
        database.leaveDao().updateLeaveRequest(updated)
        return BackendResult.Success(updated.toDto())
    }

    override suspend fun myNotifications(): BackendResult<List<NotificationDto>> {
        val list = database.notificationDao().getNotificationsForUser(currentEmployeeId).first()
        val dtos = list.map {
            NotificationDto(
                id = it.notificationId,
                title = it.title,
                message = it.message,
                type = it.type,
                timestamp = isoFormat.format(Date(it.timestampUtc))
            )
        }
        return BackendResult.Success(dtos)
    }

    override suspend fun myProfile(): BackendResult<ProfileDto> {
        val user = database.userDao().getUserByEmployeeId(currentEmployeeId)
            ?: database.userDao().getAllUsers().first().firstOrNull()

        val project = database.projectDao().getAllProjects().first().firstOrNull()

        val profile = ProfileDto(
            fullName = user?.fullName ?: "Ahmed Ali Al-Balushi",
            employeeCode = user?.employeeId ?: currentEmployeeId,
            role = user?.role ?: "WORKER",
            department = "Civil Works Infrastructure",
            email = user?.email ?: "ahmed.demo@artify.om",
            phone = user?.phone ?: "+968 9123 4567",
            companyName = "Artify Contracting LLC (Demo Mode)",
            isDemo = true,
            projectName = project?.projectName ?: "Muscat Commercial Bay Tower",
            projectCode = project?.projectId ?: "PRJ-001",
            projectAddress = project?.address ?: "Al Khuwair, Muscat, Sultanate of Oman",
            projectLatitude = project?.latitude ?: 23.5880,
            projectLongitude = project?.longitude ?: 58.3829,
            geofenceRadiusMeters = project?.geofenceRadiusMeters ?: 150.0
        )
        return BackendResult.Success(profile)
    }

    override suspend fun getMySelfieUrl(storagePath: String): BackendResult<String> {
        return BackendResult.Success("https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=300")
    }

    override suspend fun getTeamSelfieUrl(storagePath: String): BackendResult<String> {
        return BackendResult.Success("https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=300")
    }

    override suspend fun sites(): BackendResult<List<SiteDto>> {
        val projects = database.projectDao().getAllProjects().first()
        val dtos = projects.map {
            SiteDto(
                id = it.projectId,
                projectCode = it.projectId,
                name = it.projectName,
                address = it.address,
                geofenceRadiusMeters = it.geofenceRadiusMeters,
                isActive = it.status == "ACTIVE",
                employees = listOf("ART-W-000001", "ART-S-000002")
            )
        }
        return BackendResult.Success(dtos)
    }

    override suspend fun auditLog(): BackendResult<List<AuditLogDto>> {
        val logs = database.auditDao().getAllAuditLogs().first()
        val dtos = logs.map {
            AuditLogDto(
                id = it.auditId,
                actorEmployeeId = it.actorId,
                actorRole = it.actorRole,
                action = it.action,
                entityType = it.entityType,
                entityId = it.entityId,
                reason = it.details,
                createdAt = isoFormat.format(Date(it.serverTimestampUtc))
            )
        }
        return BackendResult.Success(dtos)
    }

    override suspend fun erpOutbox(): BackendResult<List<ErpEventDto>> {
        val events = database.erpDao().getAllOutboxEvents().first()
        val dtos = events.map {
            ErpEventDto(
                id = it.eventId,
                eventType = it.eventType,
                status = it.status,
                idempotencyKey = it.idempotencyKey,
                responseRef = it.erpResponseRef,
                createdAt = isoFormat.format(Date(it.createdAtUtc))
            )
        }
        return BackendResult.Success(dtos)
    }

    override suspend fun supervisorMetrics(): BackendResult<SupervisorMetricsDto> {
        val allAttendance = database.attendanceDao().getAllAttendance().first()
        val pending = allAttendance.count { it.state == "PENDING_APPROVAL" }
        val active = allAttendance.count { it.endTimeUtc == null && it.state != "CANCELLED" }
        val pendingLeaves = database.leaveDao().getPendingLeaveRequests().first().size

        val metrics = SupervisorMetricsDto(
            present = active.coerceAtLeast(1),
            working = active.coerceAtLeast(1),
            attendancePending = pending,
            leavePending = pendingLeaves,
            onLeave = 1
        )
        return BackendResult.Success(metrics)
    }

    override suspend fun roster(): BackendResult<List<RosterEmployeeDto>> {
        val users = database.userDao().getAllUsers().first()
        val dtos = users.map {
            RosterEmployeeDto(
                id = it.userId,
                employeeCode = it.employeeId,
                fullName = it.fullName,
                role = it.role,
                employmentStatus = it.status,
                project = ProjectSummary(name = "Muscat Commercial Bay Tower")
            )
        }
        return BackendResult.Success(dtos)
    }

    override suspend fun attendanceRoster(): BackendResult<List<AttendanceShiftDto>> {
        val list = database.attendanceDao().getAllAttendance().first()
        val dtos = list.take(30).map { it.toShiftDto(displayDateFormat, isoFormat) }
        return BackendResult.Success(dtos)
    }

    private fun AttendanceEntity.toShiftDto(
        displayDateFormat: SimpleDateFormat,
        isoFormat: SimpleDateFormat
    ): AttendanceShiftDto {
        val rawDate = this.shiftDate
        // Ensure DD-MM-YYYY format
        val formattedDate = runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(rawDate)
            if (parsed != null) displayDateFormat.format(parsed) else rawDate
        }.getOrDefault(rawDate)

        val clockInTime = this.startTimeUtc?.let { isoFormat.format(Date(it)) }
        val clockOutTime = this.endTimeUtc?.let { isoFormat.format(Date(it)) }

        val statusMapped = when (this.state) {
            "OPEN" -> "OPEN"
            "PENDING_APPROVAL" -> "PENDING_REVIEW"
            "APPROVED" -> "APPROVED"
            "REJECTED" -> "REJECTED"
            else -> this.state
        }

        return AttendanceShiftDto(
            id = this.attendanceId,
            employeeId = this.employeeId,
            projectId = this.projectId,
            shiftDate = formattedDate,
            clockInEventId = this.attendanceId + "-in",
            clockOutEventId = if (this.endTimeUtc != null) this.attendanceId + "-out" else null,
            totalWorkedMinutes = this.totalWorkedMinutes,
            status = statusMapped,
            complianceFlag = if (this.startGeofenceStatus == "OUTSIDE") "OUTSIDE_GEOFENCE" else "OK",
            reviewedBy = this.reviewedBySupervisorId,
            reviewedAt = this.reviewedAtUtc?.let { isoFormat.format(Date(it)) },
            reviewComment = this.supervisorComment ?: this.rejectionReason,
            clockIn = AttendanceEventSummary(
                serverTimestamp = clockInTime,
                geofenceStatus = this.startGeofenceStatus,
                distanceFromProjectMeters = this.startDistanceFromProjectMeters,
                selfieStoragePath = this.startSelfieData,
                isMockLocation = this.isStartMockLocation,
                deviceId = this.deviceId
            ),
            clockOut = if (this.endTimeUtc != null) {
                AttendanceEventSummary(
                    serverTimestamp = clockOutTime,
                    geofenceStatus = this.endGeofenceStatus,
                    distanceFromProjectMeters = this.endDistanceFromProjectMeters,
                    selfieStoragePath = this.endSelfieData,
                    isMockLocation = this.isEndMockLocation,
                    deviceId = this.deviceId
                )
            } else null,
            employee = EmployeeSummary(
                fullName = this.employeeName,
                employeeCode = this.employeeId,
                role = this.employeeRole
            ),
            project = ProjectSummary(name = this.projectName)
        )
    }

    private fun LeaveRequestEntity.toDto(): LeaveRequestDto {
        return LeaveRequestDto(
            id = this.requestId,
            employeeId = this.employeeId,
            leaveType = this.type,
            startDate = this.startDate,
            endDate = this.endDate,
            totalDays = this.totalDays.toDouble(),
            reason = this.reason,
            status = this.status,
            decisionReason = this.rejectionReason,
            employee = EmployeeSummary(
                fullName = this.employeeName,
                employeeCode = this.employeeId,
                role = this.employeeRole
            )
        )
    }
}
