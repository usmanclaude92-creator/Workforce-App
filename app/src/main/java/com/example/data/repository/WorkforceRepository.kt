package com.example.data.repository

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.entity.*
import com.example.model.*
import com.example.notifications.FcmNotificationManager
import com.example.security.PasswordHasher
import com.example.server.LocationUtils
import com.example.server.NtpTimeService
import com.example.server.ServerAuthorityEngine
import com.example.server.ServerTimestampResult
import com.example.sync.FirestoreSyncManager
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class WorkforceRepository(
    private val db: AppDatabase,
    private val context: Context? = null
) {

    val firestoreSyncManager: FirestoreSyncManager? = context?.let {
        FirestoreSyncManager.getInstance(it, db)
    }

    private val userDao = db.userDao()
    private val projectDao = db.projectDao()
    private val attendanceDao = db.attendanceDao()
    private val leaveDao = db.leaveDao()
    private val auditDao = db.auditDao()
    private val notificationDao = db.notificationDao()
    private val erpDao = db.erpDao()

    suspend fun seedInitialDataIfEmpty() {
        if (userDao.getUserCount() > 0) return

        // 1. Projects
        val projectA = ProjectEntity(
            projectId = "PRJ-001",
            projectName = "Muscat Construction Site A",
            code = "MCT-A",
            latitude = 23.5880,
            longitude = 58.3829,
            geofenceRadiusMeters = 150.0,
            maxGpsAccuracyMeters = 100.0,
            address = "Al Ghubrah North, Muscat",
            status = "ACTIVE",
            companyId = "CMP-ARTIFY-01"
        )
        val projectB = ProjectEntity(
            projectId = "PRJ-002",
            projectName = "Al Khuwair Commercial Tower",
            code = "AK-TWR",
            latitude = 23.6000,
            longitude = 58.4000,
            geofenceRadiusMeters = 200.0,
            maxGpsAccuracyMeters = 80.0,
            address = "Dohat Al Adab St, Al Khuwair",
            status = "ACTIVE",
            companyId = "CMP-ARTIFY-01"
        )
        val projectC = ProjectEntity(
            projectId = "PRJ-003",
            projectName = "Sohar Industrial Logistics Hub",
            code = "SHR-LOG",
            latitude = 24.3461,
            longitude = 56.7075,
            geofenceRadiusMeters = 300.0,
            maxGpsAccuracyMeters = 120.0,
            address = "Port of Sohar Freezone",
            status = "ACTIVE",
            companyId = "CMP-ARTIFY-01"
        )
        projectDao.insertProjects(listOf(projectA, projectB, projectC))

        // 2. Users (Worker, Staff, Supervisor)
        val workerUser = UserEntity(
            userId = "USR-W-01",
            employeeId = "ART-W-000001",
            role = UserRole.WORKER.name,
            fullName = "Ahmed Ali Al-Balushi",
            phone = "+968 9123 4567",
            email = "worker@artify.demo",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-001",
            department = "Civil Construction",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 30
        )

        val staffUser = UserEntity(
            userId = "USR-S-01",
            employeeId = "ART-S-000001",
            role = UserRole.STAFF.name,
            fullName = "Fatima Al-Harthy",
            phone = "+968 9234 5678",
            email = "staff@artify.demo",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-002",
            department = "Site Operations & Safety",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1494790108377-be9c29b29330",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 30
        )

        val supervisorUser = UserEntity(
            userId = "USR-SUP-01",
            employeeId = "ART-SUP-000001",
            role = UserRole.SUPERVISOR.name,
            fullName = "Tariq Al-Said",
            phone = "+968 9345 6789",
            email = "supervisor@artify.demo",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-001",
            department = "Workforce Supervision & HR",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1500648767791-00dcc994a43e",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 60
        )

        val artifyStaffUser = UserEntity(
            userId = "USR-SUP-ARTIFY",
            employeeId = "ART-SUP-000099",
            role = UserRole.SUPERVISOR.name,
            fullName = "Artify Staff Admin",
            phone = "+968 9999 8888",
            email = "artifystaff@gmail.com",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-001",
            department = "Workforce Operations & Payroll",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 60
        )

        val worker2 = UserEntity(
            userId = "USR-W-02",
            employeeId = "ART-W-000002",
            role = UserRole.WORKER.name,
            fullName = "Khalid Mansoor",
            phone = "+968 9456 7890",
            email = "khalid@artify.demo",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-001",
            department = "Electrical Engineering",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1534528741775-53994a69daeb",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 15
        )

        val worker3 = UserEntity(
            userId = "USR-W-03",
            employeeId = "ART-W-000003",
            role = UserRole.WORKER.name,
            fullName = "Salim Al-Jabri",
            phone = "+968 9567 8901",
            email = "salim@artify.demo",
            passwordHash = PasswordHasher.hash("password123"),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = "PRJ-003",
            department = "Heavy Machinery",
            status = "ACTIVE",
            avatarUrl = "https://images.unsplash.com/photo-1519085360753-af0119f7cbe7",
            createdAtUtc = System.currentTimeMillis() - 86400000L * 10
        )

        userDao.insertUsers(listOf(workerUser, staffUser, supervisorUser, artifyStaffUser, worker2, worker3))

        // 3. Historical Attendance Records
        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val yesterdayUtc = serverTime.timestampUtc - 86400000L

        // Record 1: Yesterday Approved Shift for Worker
        val record1 = AttendanceEntity(
            attendanceId = "ATT-20260824-001",
            employeeId = workerUser.employeeId,
            employeeName = workerUser.fullName,
            employeeRole = workerUser.role,
            projectId = projectA.projectId,
            projectName = projectA.projectName,
            shiftDate = "2026-08-24",
            startTimeUtc = yesterdayUtc - (8 * 3600000L),
            startTimeFormatted = "24 Aug 2026 08:00:15",
            endTimeUtc = yesterdayUtc,
            endTimeFormatted = "24 Aug 2026 16:02:40",
            totalWorkedMinutes = 482,
            startSelfieData = "selfie_hash_verified_001",
            endSelfieData = "selfie_hash_verified_002",
            startLatitude = 23.5881,
            startLongitude = 58.3830,
            startAccuracy = 12.5f,
            startGeofenceStatus = GeofenceStatus.INSIDE_GEOFENCE.name,
            startDistanceFromProjectMeters = 18.4,
            endLatitude = 23.5879,
            endLongitude = 58.3828,
            endAccuracy = 14.0f,
            endGeofenceStatus = GeofenceStatus.INSIDE_GEOFENCE.name,
            endDistanceFromProjectMeters = 22.1,
            state = AttendanceState.APPROVED.name,
            verificationStatus = VerificationStatus.VERIFIED.name,
            supervisorComment = "Regular on-time full shift verified.",
            reviewedBySupervisorId = supervisorUser.employeeId,
            reviewedAtUtc = yesterdayUtc + 3600000L,
            syncedToErp = true,
            erpIdempotencyKey = "CMP-ARTIFY-01_${workerUser.employeeId}_ATT-20260824-001",
            createdAtUtc = yesterdayUtc - (8 * 3600000L)
        )

        // Record 2: Today Pending Approval for Khalid Mansoor
        val record2 = AttendanceEntity(
            attendanceId = "ATT-20260825-002",
            employeeId = worker2.employeeId,
            employeeName = worker2.fullName,
            employeeRole = worker2.role,
            projectId = projectA.projectId,
            projectName = projectA.projectName,
            shiftDate = serverTime.dateString,
            startTimeUtc = serverTime.timestampUtc - 18000000L,
            startTimeFormatted = "25 Aug 2026 07:58:10",
            endTimeUtc = serverTime.timestampUtc - 3600000L,
            endTimeFormatted = "25 Aug 2026 12:00:20",
            totalWorkedMinutes = 242,
            startSelfieData = "selfie_khalid_start_hash",
            endSelfieData = "selfie_khalid_end_hash",
            startLatitude = 23.5882,
            startLongitude = 58.3831,
            startAccuracy = 15.0f,
            startGeofenceStatus = GeofenceStatus.INSIDE_GEOFENCE.name,
            startDistanceFromProjectMeters = 31.2,
            endLatitude = 23.5880,
            endLongitude = 58.3829,
            endAccuracy = 10.0f,
            endGeofenceStatus = GeofenceStatus.INSIDE_GEOFENCE.name,
            endDistanceFromProjectMeters = 8.5,
            state = AttendanceState.PENDING_APPROVAL.name,
            verificationStatus = VerificationStatus.VERIFIED.name,
            createdAtUtc = serverTime.timestampUtc - 18000000L
        )

        attendanceDao.insertAttendance(record1)
        attendanceDao.insertAttendance(record2)

        // 4. Leave Requests
        val leave1 = LeaveRequestEntity(
            requestId = "LV-20260820-001",
            employeeId = worker3.employeeId,
            employeeName = worker3.fullName,
            employeeRole = worker3.role,
            type = LeaveType.SICK_LEAVE.name,
            startDate = "2026-08-20",
            endDate = "2026-08-21",
            totalDays = 2,
            reason = "Medical checkup & doctor certified rest.",
            status = LeaveStatus.APPROVED.name,
            submittedAtUtc = serverTime.timestampUtc - (5 * 86400000L),
            approvedAtUtc = serverTime.timestampUtc - (5 * 86400000L) + 7200000L,
            approvedBy = supervisorUser.fullName
        )

        val leave2 = LeaveRequestEntity(
            requestId = "LV-20260825-002",
            employeeId = staffUser.employeeId,
            employeeName = staffUser.fullName,
            employeeRole = staffUser.role,
            type = LeaveType.CASUAL_LEAVE.name,
            startDate = "2026-08-28",
            endDate = "2026-08-29",
            totalDays = 2,
            reason = "Family occasion in Nizwa.",
            status = LeaveStatus.PENDING.name,
            submittedAtUtc = serverTime.timestampUtc - 3600000L
        )

        leaveDao.insertLeaveRequest(leave1)
        leaveDao.insertLeaveRequest(leave2)

        // 5. Initial Audit Logs
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-INIT-001",
                actorId = supervisorUser.employeeId,
                actorName = supervisorUser.fullName,
                actorRole = supervisorUser.role,
                action = AuditAction.USER_REGISTERED.name,
                entityType = "USER",
                entityId = workerUser.employeeId,
                serverTimestampUtc = serverTime.timestampUtc - 86400000L * 30,
                details = "Registered employee ${workerUser.fullName} (${workerUser.employeeId}) with assigned project ${projectA.projectName}."
            )
        )
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-INIT-002",
                actorId = supervisorUser.employeeId,
                actorName = supervisorUser.fullName,
                actorRole = supervisorUser.role,
                action = AuditAction.ATTENDANCE_APPROVED.name,
                entityType = "ATTENDANCE",
                entityId = record1.attendanceId,
                serverTimestampUtc = yesterdayUtc + 3600000L,
                details = "Approved full shift attendance for ${workerUser.fullName}. Total worked 482 minutes. Geofence & selfie verified."
            )
        )

        // 6. Notification
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-001",
                recipientId = "ALL",
                title = "Welcome to Artify Workforce",
                message = "Official shift attendance requires GPS geofencing, selfie photo evidence, and authoritative server timestamps.",
                type = "SYSTEM",
                timestampUtc = serverTime.timestampUtc - 86400000L,
                isRead = false
            )
        )
    }

    // --- Authentication & User Operations ---

    suspend fun login(email: String, passwordAttempt: String): Result<UserEntity> {
        seedInitialDataIfEmpty()
        val trimmedEmail = email.trim()
        var user = userDao.getUserByEmail(trimmedEmail)

        if (user == null) {
            // No Civil ID / HCMS employee-master lookup exists yet (tracked separately) — until
            // that lands, an unrecognized work email is auto-provisioned as a plain WORKER only.
            // It must never be able to grant itself an elevated role by its email address.
            val serverTime = ServerAuthorityEngine.getServerTimestamp()
            val role = UserRole.WORKER
            val count = userDao.getUserCount() + 1
            val empId = role.idPrefix + String.format("%06d", count)
            val name = trimmedEmail.substringBefore("@").replace(".", " ").split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

            val autoUser = UserEntity(
                userId = "USR-" + UUID.randomUUID().toString().take(8).uppercase(),
                employeeId = empId,
                role = role.name,
                fullName = if (name.isNotBlank()) name else "Enterprise Staff",
                phone = "+968 9123 0000",
                email = trimmedEmail,
                passwordHash = PasswordHasher.hash(passwordAttempt.trim().ifBlank { "password123" }),
                companyId = "CMP-ARTIFY-01",
                companyName = "Artify Demo Company",
                assignedProjectId = "PRJ-001",
                department = "Site Engineering",
                status = "ACTIVE",
                avatarUrl = "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d",
                createdAtUtc = serverTime.timestampUtc
            )
            userDao.insertUser(autoUser)
            user = autoUser
        } else if (!PasswordHasher.verify(passwordAttempt.trim(), user.passwordHash)) {
            return Result.failure(Exception("Incorrect password provided."))
        }

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-LOG-" + UUID.randomUUID().toString().take(8),
                actorId = user.employeeId,
                actorName = user.fullName,
                actorRole = user.role,
                action = AuditAction.LOGIN.name,
                entityType = "AUTH",
                entityId = user.userId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "User ${user.fullName} logged into Artify Workforce."
            )
        )

        return Result.success(user)
    }

    suspend fun register(
        fullName: String,
        email: String,
        phone: String,
        role: UserRole,
        assignedProjectId: String,
        department: String,
        password: String
    ): Result<UserEntity> {
        if (userDao.getUserByEmail(email.trim()) != null) {
            return Result.failure(Exception("An account with email $email already exists."))
        }

        val count = userDao.getUserCount() + 1
        val empId = role.idPrefix + String.format("%06d", count)
        val userId = "USR-" + UUID.randomUUID().toString().take(8).uppercase()
        val serverTime = ServerAuthorityEngine.getServerTimestamp()

        val newUser = UserEntity(
            userId = userId,
            employeeId = empId,
            role = role.name,
            fullName = fullName.trim(),
            phone = phone.trim(),
            email = email.trim(),
            passwordHash = PasswordHasher.hash(password.trim()),
            companyId = "CMP-ARTIFY-01",
            companyName = "Artify Demo Company",
            assignedProjectId = assignedProjectId,
            department = department.trim(),
            status = "ACTIVE",
            avatarUrl = "",
            createdAtUtc = serverTime.timestampUtc
        )

        userDao.insertUser(newUser)

        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-REG-" + UUID.randomUUID().toString().take(8),
                actorId = empId,
                actorName = fullName,
                actorRole = role.name,
                action = AuditAction.USER_REGISTERED.name,
                entityType = "USER",
                entityId = empId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Registered new $role ($empId) assigned to project $assignedProjectId."
            )
        )

        return Result.success(newUser)
    }

    suspend fun getUserByEmployeeId(employeeId: String): UserEntity? =
        userDao.getUserByEmployeeId(employeeId)

    suspend fun updateUserProfilePicture(employeeId: String, avatarUrlOrPath: String): Result<UserEntity> {
        val user = userDao.getUserByEmployeeId(employeeId)
            ?: return Result.failure(Exception("Employee $employeeId not found."))

        val updatedUser = user.copy(avatarUrl = avatarUrlOrPath)
        userDao.insertUser(updatedUser)

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-PIC-" + UUID.randomUUID().toString().take(8),
                actorId = employeeId,
                actorName = user.fullName,
                actorRole = user.role,
                action = AuditAction.PROFILE_UPDATED.name,
                entityType = "USER",
                entityId = employeeId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Updated profile picture using device camera capture."
            )
        )

        return Result.success(updatedUser)
    }

    fun getAllUsers(): Flow<List<UserEntity>> = userDao.getAllUsers()
    fun getUsersByRole(role: String): Flow<List<UserEntity>> = userDao.getUsersByRole(role)

    suspend fun assignShiftOrSchedule(
        context: Context,
        supervisor: UserEntity,
        employeeId: String,
        projectId: String,
        shiftDate: String,
        shiftTiming: String,
        notes: String?,
        isScheduleChange: Boolean = false,
        changeReason: String? = null,
        oldTiming: String? = null
    ): Result<Unit> {
        val employee = userDao.getUserByEmployeeId(employeeId)
            ?: return Result.failure(Exception("Employee $employeeId not found."))
        val project = projectDao.getProjectById(projectId)
            ?: return Result.failure(Exception("Project site $projectId not found."))

        // Update assigned project if changed
        if (employee.assignedProjectId != projectId) {
            userDao.insertUser(employee.copy(assignedProjectId = projectId))
        }

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val actionName = if (isScheduleChange) AuditAction.SCHEDULE_CHANGED.name else AuditAction.SHIFT_ASSIGNED.name
        val auditDetails = if (isScheduleChange) {
            "Schedule for ${employee.fullName} ($employeeId) at ${project.projectName} changed to $shiftTiming on $shiftDate by ${supervisor.fullName}. Reason: ${changeReason ?: "Routine adjustment"}."
        } else {
            "Assigned new shift to ${employee.fullName} ($employeeId) at ${project.projectName} on $shiftDate ($shiftTiming) by ${supervisor.fullName}."
        }

        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-SFT-" + UUID.randomUUID().toString().take(8),
                actorId = supervisor.employeeId,
                actorName = supervisor.fullName,
                actorRole = supervisor.role,
                action = actionName,
                entityType = "SHIFT_SCHEDULE",
                entityId = employeeId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = auditDetails
            )
        )

        if (isScheduleChange) {
            FcmNotificationManager.dispatchScheduleChangeAlert(
                context = context,
                employeeId = employee.employeeId,
                employeeName = employee.fullName,
                projectName = project.projectName,
                shiftDate = shiftDate,
                newTiming = shiftTiming,
                oldTiming = oldTiming,
                supervisorName = supervisor.fullName,
                changeReason = changeReason
            )
        } else {
            FcmNotificationManager.dispatchShiftAssignmentAlert(
                context = context,
                employeeId = employee.employeeId,
                employeeName = employee.fullName,
                projectName = project.projectName,
                shiftDate = shiftDate,
                shiftTiming = shiftTiming,
                supervisorName = supervisor.fullName,
                notes = notes
            )
        }

        return Result.success(Unit)
    }

    // --- Projects ---

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectDao.getAllProjects()
    suspend fun getProjectById(projectId: String): ProjectEntity? = projectDao.getProjectById(projectId)

    // --- Attendance Operations (Shift Lifecycle & Server Authority) ---

    suspend fun getActiveShift(employeeId: String): AttendanceEntity? =
        attendanceDao.getAnyActiveShift(employeeId)

    suspend fun startShift(
        employee: UserEntity,
        project: ProjectEntity,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieData: String,
        deviceId: String
    ): Result<AttendanceEntity> {
        // 1. Guard against duplicate active shifts
        val existingActive = attendanceDao.getAnyActiveShift(employee.employeeId)
        if (existingActive != null) {
            return Result.failure(Exception("Active shift already in progress since ${existingActive.startTimeFormatted}. Must end active shift first."))
        }

        // 2. Geofence/GPS/mock-location evaluation (Google Location Services & Server Engine).
        // These are compliance signals for Head Office HR / supervisor review, NOT a gate on
        // attendance: the shift is always recorded regardless of location result, and any
        // violation (outside radius, low accuracy, mock location, or GPS unavailable) is
        // captured on the record via startGeofenceStatus/verificationStatus below.
        val geofenceResult = ServerAuthorityEngine.evaluateGeofence(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            isMockLocation = isMockLocation,
            project = project
        )

        val verificationStatus = if (geofenceResult.isInside && geofenceResult.isAccuracyAcceptable && !isMockLocation) {
            VerificationStatus.VERIFIED
        } else {
            VerificationStatus.FLAGGED
        }

        // 3. Authoritative Server Timestamp Handshake
        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val attendanceId = "ATT-" + serverTime.dateString.replace("-", "") + "-" + UUID.randomUUID().toString().take(6).uppercase()

        val attendance = AttendanceEntity(
            attendanceId = attendanceId,
            employeeId = employee.employeeId,
            employeeName = employee.fullName,
            employeeRole = employee.role,
            projectId = project.projectId,
            projectName = project.projectName,
            shiftDate = serverTime.dateString,
            startTimeUtc = serverTime.timestampUtc,
            startTimeFormatted = serverTime.displayFormatted,
            endTimeUtc = null,
            endTimeFormatted = null,
            totalWorkedMinutes = 0,
            startSelfieData = selfieData,
            endSelfieData = null,
            startLatitude = latitude,
            startLongitude = longitude,
            startAccuracy = accuracy,
            startGeofenceStatus = geofenceResult.status.name,
            startDistanceFromProjectMeters = geofenceResult.distanceMeters,
            isStartMockLocation = isMockLocation,
            state = AttendanceState.SUBMITTED.name,
            verificationStatus = verificationStatus.name,
            deviceId = deviceId,
            createdAtUtc = serverTime.timestampUtc
        )

        attendanceDao.insertAttendance(attendance)

        // Queue clock-in attempt into Firestore persistent cache (queues offline, auto-syncs when online)
        firestoreSyncManager?.queueClockIn(attendance)

        // Audit Trail (Synchronized with Head Office Payroll)
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-STRT-" + UUID.randomUUID().toString().take(8),
                actorId = employee.employeeId,
                actorName = employee.fullName,
                actorRole = employee.role,
                action = AuditAction.SHIFT_STARTED.name,
                entityType = "ATTENDANCE",
                entityId = attendanceId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "[HEAD OFFICE PAYROLL NOTIFIED]: Shift started at ${project.projectName}. Location: ${geofenceResult.status.displayName} (${geofenceResult.distanceMeters.toInt()}m from site). GPS Lat: $latitude, Lng: $longitude, Accuracy: ${accuracy?.toInt() ?: 0}m. Attendance recorded."
            )
        )

        return Result.success(attendance)
    }

    suspend fun endShift(
        employee: UserEntity,
        project: ProjectEntity,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        isMockLocation: Boolean,
        selfieData: String? = null,
        deviceId: String
    ): Result<AttendanceEntity> {
        val activeShift = attendanceDao.getAnyActiveShift(employee.employeeId)
            ?: return Result.failure(Exception("No active shift found to end. Start a shift first."))

        if (activeShift.endTimeUtc != null) {
            return Result.failure(Exception("Shift already ended at ${activeShift.endTimeFormatted}."))
        }

        // Validate End Geofence on Server
        val geofenceResult = ServerAuthorityEngine.evaluateGeofence(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            isMockLocation = isMockLocation,
            project = project
        )

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val workedMinutes = if (activeShift.startTimeUtc != null) {
            ((serverTime.timestampUtc - activeShift.startTimeUtc) / 60000L).toInt().coerceAtLeast(1)
        } else {
            0
        }

        val updatedShift = activeShift.copy(
            endTimeUtc = serverTime.timestampUtc,
            endTimeFormatted = serverTime.displayFormatted,
            totalWorkedMinutes = workedMinutes,
            endSelfieData = selfieData,
            endLatitude = latitude,
            endLongitude = longitude,
            endAccuracy = accuracy,
            endGeofenceStatus = geofenceResult.status.name,
            endDistanceFromProjectMeters = geofenceResult.distanceMeters,
            isEndMockLocation = isMockLocation,
            state = AttendanceState.PENDING_APPROVAL.name,
            verificationStatus = if (geofenceResult.isInside && geofenceResult.isAccuracyAcceptable)
                VerificationStatus.VERIFIED.name
            else
                VerificationStatus.FLAGGED.name,
            syncedToFirestore = false,
            firestoreSyncStatus = "QUEUED_OFFLINE"
        )

        attendanceDao.updateAttendance(updatedShift)

        // Queue check-out record to Firestore persistent cache (stored offline in Room + Firestore, auto-synced once online)
        firestoreSyncManager?.queueClockOut(updatedShift)

        // Audit Trail (Synchronized with Head Office Payroll)
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-END-" + UUID.randomUUID().toString().take(8),
                actorId = employee.employeeId,
                actorName = employee.fullName,
                actorRole = employee.role,
                action = AuditAction.SHIFT_ENDED.name,
                entityType = "ATTENDANCE",
                entityId = activeShift.attendanceId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "[HEAD OFFICE PAYROLL NOTIFIED]: Shift ended by ${employee.fullName} ($workedMinutes mins). End location: ${geofenceResult.status.displayName} (${geofenceResult.distanceMeters.toInt()}m). Synced for payroll processing."
            )
        )

        // Notification for Supervisors & Head Office Payroll
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-SUB-" + UUID.randomUUID().toString().take(8),
                recipientId = "ALL_SUPERVISORS",
                title = "Shift Completed • Head Office Payroll Synced",
                message = "${employee.fullName} (${employee.employeeId}) completed shift ($workedMinutes mins) at ${project.projectName}. Location: ${geofenceResult.status.displayName}.",
                type = "ATTENDANCE",
                timestampUtc = serverTime.timestampUtc
            )
        )

        return Result.success(updatedShift)
    }

    suspend fun approveAttendance(
        attendanceId: String,
        supervisor: UserEntity,
        comment: String?
    ): Result<AttendanceEntity> {
        if (supervisor.role != UserRole.SUPERVISOR.name) {
            return Result.failure(Exception("Permission denied: Only supervisors can approve attendance records."))
        }

        val record = attendanceDao.getAttendanceById(attendanceId)
            ?: return Result.failure(Exception("Attendance record $attendanceId not found."))

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val idempotencyKey = ServerAuthorityEngine.generateErpIdempotencyKey(
            companyId = supervisor.companyId,
            employeeId = record.employeeId,
            attendanceId = record.attendanceId
        )

        val approvedRecord = record.copy(
            state = AttendanceState.APPROVED.name,
            verificationStatus = VerificationStatus.VERIFIED.name,
            supervisorComment = comment?.takeIf { it.isNotBlank() } ?: "Approved by ${supervisor.fullName}",
            reviewedBySupervisorId = supervisor.employeeId,
            reviewedAtUtc = serverTime.timestampUtc,
            syncedToErp = true,
            erpIdempotencyKey = idempotencyKey
        )

        attendanceDao.updateAttendance(approvedRecord)

        // ERP Integration Outbox Event
        erpDao.insertOutboxEvent(
            ErpOutboxEntity(
                idempotencyKey = idempotencyKey,
                eventId = "EVT-ERP-" + UUID.randomUUID().toString().take(8),
                eventType = "ATTENDANCE_APPROVED",
                payloadJson = """{"attendanceId":"${record.attendanceId}","employeeId":"${record.employeeId}","workedMinutes":${record.totalWorkedMinutes},"shiftDate":"${record.shiftDate}","approvedBy":"${supervisor.employeeId}"}""",
                status = "SYNCED",
                attempts = 1,
                createdAtUtc = serverTime.timestampUtc,
                lastAttemptUtc = serverTime.timestampUtc,
                erpResponseRef = "ERP-SYNC-OK-${UUID.randomUUID().toString().take(6).uppercase()}"
            )
        )

        // Audit Trail
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-APPR-" + UUID.randomUUID().toString().take(8),
                actorId = supervisor.employeeId,
                actorName = supervisor.fullName,
                actorRole = supervisor.role,
                action = AuditAction.ATTENDANCE_APPROVED.name,
                entityType = "ATTENDANCE",
                entityId = attendanceId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Supervisor ${supervisor.fullName} approved attendance for ${record.employeeName}. Comment: $comment"
            )
        )

        // Notify Employee
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-APPR-" + UUID.randomUUID().toString().take(8),
                recipientId = record.employeeId,
                title = "Shift Attendance Approved",
                message = "Your attendance record for ${record.shiftDate} was approved by ${supervisor.fullName}.",
                type = "ATTENDANCE",
                timestampUtc = serverTime.timestampUtc
            )
        )

        // Dispatch Automated FCM Push Notification
        context?.let { ctx ->
            FcmNotificationManager.dispatchAttendanceApprovalPushNotification(
                context = ctx,
                attendance = approvedRecord,
                supervisor = supervisor,
                isApproved = true,
                commentOrReason = comment
            )
        }

        return Result.success(approvedRecord)
    }

    suspend fun rejectAttendance(
        attendanceId: String,
        supervisor: UserEntity,
        rejectionReason: String
    ): Result<AttendanceEntity> {
        if (supervisor.role != UserRole.SUPERVISOR.name) {
            return Result.failure(Exception("Permission denied: Only supervisors can reject attendance records."))
        }

        if (rejectionReason.isBlank()) {
            return Result.failure(Exception("A rejection reason is mandatory when rejecting attendance."))
        }

        val record = attendanceDao.getAttendanceById(attendanceId)
            ?: return Result.failure(Exception("Attendance record $attendanceId not found."))

        val serverTime = ServerAuthorityEngine.getServerTimestamp()

        val rejectedRecord = record.copy(
            state = AttendanceState.REJECTED.name,
            verificationStatus = VerificationStatus.REJECTED.name,
            rejectionReason = rejectionReason.trim(),
            reviewedBySupervisorId = supervisor.employeeId,
            reviewedAtUtc = serverTime.timestampUtc
        )

        attendanceDao.updateAttendance(rejectedRecord)

        // Audit Trail
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-REJ-" + UUID.randomUUID().toString().take(8),
                actorId = supervisor.employeeId,
                actorName = supervisor.fullName,
                actorRole = supervisor.role,
                action = AuditAction.ATTENDANCE_REJECTED.name,
                entityType = "ATTENDANCE",
                entityId = attendanceId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Supervisor ${supervisor.fullName} rejected attendance for ${record.employeeName}. Reason: $rejectionReason"
            )
        )

        // Notify Employee
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-REJ-" + UUID.randomUUID().toString().take(8),
                recipientId = record.employeeId,
                title = "Shift Attendance Rejected",
                message = "Your attendance for ${record.shiftDate} was rejected: $rejectionReason",
                type = "ATTENDANCE",
                timestampUtc = serverTime.timestampUtc
            )
        )

        // Dispatch Automated FCM Push Notification
        context?.let { ctx ->
            FcmNotificationManager.dispatchAttendanceApprovalPushNotification(
                context = ctx,
                attendance = rejectedRecord,
                supervisor = supervisor,
                isApproved = false,
                commentOrReason = rejectionReason
            )
        }

        return Result.success(rejectedRecord)
    }

    // --- Leave Management ---

    suspend fun submitLeaveRequest(
        employee: UserEntity,
        type: LeaveType,
        startDate: String,
        endDate: String,
        totalDays: Int,
        reason: String
    ): Result<LeaveRequestEntity> {
        if (reason.isBlank()) {
            return Result.failure(Exception("Reason for leave/absence is required."))
        }
        if (totalDays <= 0) {
            return Result.failure(Exception("Leave duration must be at least 1 day."))
        }

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val requestId = "LV-" + serverTime.dateString.replace("-", "") + "-" + UUID.randomUUID().toString().take(6).uppercase()

        val leaveRequest = LeaveRequestEntity(
            requestId = requestId,
            employeeId = employee.employeeId,
            employeeName = employee.fullName,
            employeeRole = employee.role,
            type = type.name,
            startDate = startDate,
            endDate = endDate,
            totalDays = totalDays,
            reason = reason.trim(),
            status = LeaveStatus.PENDING.name,
            submittedAtUtc = serverTime.timestampUtc
        )

        leaveDao.insertLeaveRequest(leaveRequest)

        // Audit Trail
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-LV-" + UUID.randomUUID().toString().take(8),
                actorId = employee.employeeId,
                actorName = employee.fullName,
                actorRole = employee.role,
                action = AuditAction.LEAVE_SUBMITTED.name,
                entityType = "LEAVE",
                entityId = requestId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Submitted ${type.displayName} request from $startDate to $endDate ($totalDays days)."
            )
        )

        // Notify Supervisors (Local DB + FCM Push Alert)
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-LV-" + UUID.randomUUID().toString().take(8),
                recipientId = "ALL_SUPERVISORS",
                title = "New Leave Request Submitted",
                message = "${employee.fullName} requested ${type.displayName} ($totalDays days: $startDate to $endDate).",
                type = "LEAVE_REQUEST",
                timestampUtc = serverTime.timestampUtc
            )
        )

        // Dispatch Automated FCM Push Notification to Supervisors
        try {
            context?.let { ctx ->
                FcmNotificationManager.dispatchNewLeaveRequestAlertToSupervisors(
                    context = ctx,
                    leave = leaveRequest,
                    employee = employee
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WorkforceRepo", "Failed to trigger supervisor FCM leave alert: ${e.message}")
        }

        return Result.success(leaveRequest)
    }

    suspend fun approveLeave(
        requestId: String,
        supervisor: UserEntity
    ): Result<LeaveRequestEntity> {
        if (supervisor.role != UserRole.SUPERVISOR.name) {
            return Result.failure(Exception("Permission denied: Only supervisors can approve leave requests."))
        }

        val leave = leaveDao.getLeaveRequestById(requestId)
            ?: return Result.failure(Exception("Leave request $requestId not found."))

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val updated = leave.copy(
            status = LeaveStatus.APPROVED.name,
            approvedAtUtc = serverTime.timestampUtc,
            approvedBy = supervisor.fullName
        )

        leaveDao.updateLeaveRequest(updated)

        // Audit Trail
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-LV-APPR-" + UUID.randomUUID().toString().take(8),
                actorId = supervisor.employeeId,
                actorName = supervisor.fullName,
                actorRole = supervisor.role,
                action = AuditAction.LEAVE_APPROVED.name,
                entityType = "LEAVE",
                entityId = requestId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Supervisor ${supervisor.fullName} approved ${leave.type} request for ${leave.employeeName}."
            )
        )

        // Notify Employee (Local In-App Notification)
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-LV-OK-" + UUID.randomUUID().toString().take(8),
                recipientId = leave.employeeId,
                title = "Leave Request Approved",
                message = "Your ${leave.type} request (${leave.startDate} to ${leave.endDate}) has been approved.",
                type = "LEAVE",
                timestampUtc = serverTime.timestampUtc
            )
        )

        // Dispatch Automated FCM Push Notification
        context?.let { ctx ->
            FcmNotificationManager.dispatchLeaveStatusPushNotification(
                context = ctx,
                leave = updated,
                supervisor = supervisor,
                isApproved = true
            )
        }

        return Result.success(updated)
    }

    suspend fun rejectLeave(
        requestId: String,
        supervisor: UserEntity,
        rejectionReason: String
    ): Result<LeaveRequestEntity> {
        if (supervisor.role != UserRole.SUPERVISOR.name) {
            return Result.failure(Exception("Permission denied: Only supervisors can reject leave requests."))
        }

        if (rejectionReason.isBlank()) {
            return Result.failure(Exception("A rejection reason is required."))
        }

        val leave = leaveDao.getLeaveRequestById(requestId)
            ?: return Result.failure(Exception("Leave request $requestId not found."))

        val serverTime = ServerAuthorityEngine.getServerTimestamp()
        val updated = leave.copy(
            status = LeaveStatus.REJECTED.name,
            approvedAtUtc = serverTime.timestampUtc,
            approvedBy = supervisor.fullName,
            rejectionReason = rejectionReason.trim()
        )

        leaveDao.updateLeaveRequest(updated)

        // Audit Trail
        auditDao.insertAuditLog(
            AuditLogEntity(
                auditId = "AUD-LV-REJ-" + UUID.randomUUID().toString().take(8),
                actorId = supervisor.employeeId,
                actorName = supervisor.fullName,
                actorRole = supervisor.role,
                action = AuditAction.LEAVE_REJECTED.name,
                entityType = "LEAVE",
                entityId = requestId,
                serverTimestampUtc = serverTime.timestampUtc,
                details = "Supervisor ${supervisor.fullName} rejected ${leave.type} request for ${leave.employeeName}. Reason: $rejectionReason"
            )
        )

        // Notify Employee (Local In-App Notification)
        notificationDao.insertNotification(
            NotificationEntity(
                notificationId = "NOTIF-LV-NO-" + UUID.randomUUID().toString().take(8),
                recipientId = leave.employeeId,
                title = "Leave Request Rejected",
                message = "Your ${leave.type} request was rejected: $rejectionReason",
                type = "LEAVE",
                timestampUtc = serverTime.timestampUtc
            )
        )

        // Dispatch Automated FCM Push Notification
        context?.let { ctx ->
            FcmNotificationManager.dispatchLeaveStatusPushNotification(
                context = ctx,
                leave = updated,
                supervisor = supervisor,
                isApproved = false,
                reason = rejectionReason
            )
        }

        return Result.success(updated)
    }

    // --- Flows ---

    fun getAttendanceForEmployee(employeeId: String): Flow<List<AttendanceEntity>> =
        attendanceDao.getAttendanceForEmployee(employeeId)

    fun getAllAttendance(): Flow<List<AttendanceEntity>> = attendanceDao.getAllAttendance()
    fun getPendingApprovals(): Flow<List<AttendanceEntity>> = attendanceDao.getPendingApprovals()
    fun getAttendanceForDate(date: String): Flow<List<AttendanceEntity>> = attendanceDao.getAttendanceForDate(date)

    fun getLeaveRequestsForEmployee(employeeId: String): Flow<List<LeaveRequestEntity>> =
        leaveDao.getLeaveRequestsForEmployee(employeeId)

    fun getAllLeaveRequests(): Flow<List<LeaveRequestEntity>> = leaveDao.getAllLeaveRequests()
    fun getPendingLeaveRequests(): Flow<List<LeaveRequestEntity>> = leaveDao.getPendingLeaveRequests()

    fun getAllAuditLogs(): Flow<List<AuditLogEntity>> = auditDao.getAllAuditLogs()
    fun getNotifications(employeeId: String): Flow<List<NotificationEntity>> = notificationDao.getNotificationsForUser(employeeId)
    fun getAllErpOutbox(): Flow<List<ErpOutboxEntity>> = erpDao.getAllOutboxEvents()

    suspend fun markNotificationAsRead(id: String) = notificationDao.markAsRead(id)

    // --- Firestore Offline Persistence & Sync Helpers ---

    fun getUnsyncedAttendanceCount(): Flow<Int> = attendanceDao.getUnsyncedAttendanceCount()

    suspend fun syncPendingClockIns(): Int {
        return firestoreSyncManager?.syncPendingClockIns() ?: 0
    }

    fun simulateFirestoreNetwork(enableOnline: Boolean) {
        firestoreSyncManager?.simulateNetwork(enableOnline)
    }
}
