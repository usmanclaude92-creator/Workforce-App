package com.example.data.sync

import android.content.Context
import com.example.network.AttendanceApprovalDto
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NoMobileWorkerDto
import com.example.network.NotificationDto
import com.example.network.ProfileDto
import com.example.network.ShiftCompletionLog
import com.example.network.SiteDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Last-known-good snapshot of the worker's own read data (shifts, leave, profile,
 * notifications, shift completion logs), so the app still shows real content when opened offline — not just
 * the in-flight pending-write queue. Never used as a source of truth once the server
 * is reachable again; every successful network read overwrites it immediately.
 */
class OfflineCache(context: Context) {
    private val dao = RealSyncDatabase.getInstance(context).cacheDao()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val shiftsAdapter = moshi.adapter<List<AttendanceShiftDto>>(
        Types.newParameterizedType(List::class.java, AttendanceShiftDto::class.java)
    )
    private val completionLogsAdapter = moshi.adapter<List<ShiftCompletionLog>>(
        Types.newParameterizedType(List::class.java, ShiftCompletionLog::class.java)
    )
    private val leaveAdapter = moshi.adapter<List<LeaveRequestDto>>(
        Types.newParameterizedType(List::class.java, LeaveRequestDto::class.java)
    )
    private val notificationsAdapter = moshi.adapter<List<NotificationDto>>(
        Types.newParameterizedType(List::class.java, NotificationDto::class.java)
    )
    private val profileAdapter = moshi.adapter(ProfileDto::class.java)
    private val sitesAdapter = moshi.adapter<List<SiteDto>>(
        Types.newParameterizedType(List::class.java, SiteDto::class.java)
    )
    private val attendanceApprovalsAdapter = moshi.adapter<List<AttendanceApprovalDto>>(
        Types.newParameterizedType(List::class.java, AttendanceApprovalDto::class.java)
    )
    private val noMobileWorkerAdapter = moshi.adapter<List<NoMobileWorkerDto>>(
        Types.newParameterizedType(List::class.java, NoMobileWorkerDto::class.java)
    )

    suspend fun cacheShifts(employeeId: String, shifts: List<AttendanceShiftDto>) =
        dao.upsert(CachedJsonEntity(employeeId, KEY_SHIFTS, shiftsAdapter.toJson(shifts), System.currentTimeMillis()))

    suspend fun getCachedShifts(employeeId: String): List<AttendanceShiftDto>? =
        dao.get(employeeId, KEY_SHIFTS)?.let { runCatching { shiftsAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cacheShiftCompletionLogs(employeeId: String, logs: List<ShiftCompletionLog>) =
        dao.upsert(CachedJsonEntity(employeeId, KEY_COMPLETION_LOGS, completionLogsAdapter.toJson(logs), System.currentTimeMillis()))

    suspend fun getCachedShiftCompletionLogs(employeeId: String): List<ShiftCompletionLog>? =
        dao.get(employeeId, KEY_COMPLETION_LOGS)?.let { runCatching { completionLogsAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun recordShiftCompletionLog(employeeId: String, log: ShiftCompletionLog) {
        val existing = getCachedShiftCompletionLogs(employeeId)?.filter { it.logId != log.logId && it.shiftId != log.shiftId } ?: emptyList()
        val updated = listOf(log) + existing
        cacheShiftCompletionLogs(employeeId, updated.take(50))
    }

    suspend fun cacheLeave(employeeId: String, leave: List<LeaveRequestDto>) =
        dao.upsert(CachedJsonEntity(employeeId, KEY_LEAVE, leaveAdapter.toJson(leave), System.currentTimeMillis()))

    suspend fun getCachedLeave(employeeId: String): List<LeaveRequestDto>? =
        dao.get(employeeId, KEY_LEAVE)?.let { runCatching { leaveAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cacheNotifications(employeeId: String, notifications: List<NotificationDto>) =
        dao.upsert(CachedJsonEntity(employeeId, KEY_NOTIFICATIONS, notificationsAdapter.toJson(notifications), System.currentTimeMillis()))

    suspend fun getCachedNotifications(employeeId: String): List<NotificationDto>? =
        dao.get(employeeId, KEY_NOTIFICATIONS)?.let { runCatching { notificationsAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cacheProfile(employeeId: String, profile: ProfileDto) =
        dao.upsert(CachedJsonEntity(employeeId, KEY_PROFILE, profileAdapter.toJson(profile), System.currentTimeMillis()))

    suspend fun getCachedProfile(employeeId: String): ProfileDto? =
        dao.get(employeeId, KEY_PROFILE)?.let { runCatching { profileAdapter.fromJson(it.json) }.getOrNull() }

    // -------------------- Supervisor read data --------------------
    // Same last-known-good pattern as above, keyed by the supervisor's own employeeId,
    // so Home/Roster/Leave/Sites still show real content when the supervisor opens the
    // app offline.

    suspend fun cacheAttendanceRoster(supervisorId: String, shifts: List<AttendanceShiftDto>) =
        dao.upsert(CachedJsonEntity(supervisorId, KEY_ATTENDANCE_ROSTER, shiftsAdapter.toJson(shifts), System.currentTimeMillis()))

    suspend fun getCachedAttendanceRoster(supervisorId: String): List<AttendanceShiftDto>? =
        dao.get(supervisorId, KEY_ATTENDANCE_ROSTER)?.let { runCatching { shiftsAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cacheSites(supervisorId: String, sites: List<SiteDto>) =
        dao.upsert(CachedJsonEntity(supervisorId, KEY_SITES, sitesAdapter.toJson(sites), System.currentTimeMillis()))

    suspend fun getCachedSites(supervisorId: String): List<SiteDto>? =
        dao.get(supervisorId, KEY_SITES)?.let { runCatching { sitesAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cachePendingAttendanceApprovals(supervisorId: String, approvals: List<AttendanceApprovalDto>) =
        dao.upsert(CachedJsonEntity(supervisorId, KEY_PENDING_APPROVALS, attendanceApprovalsAdapter.toJson(approvals), System.currentTimeMillis()))

    suspend fun getCachedPendingAttendanceApprovals(supervisorId: String): List<AttendanceApprovalDto>? =
        dao.get(supervisorId, KEY_PENDING_APPROVALS)?.let { runCatching { attendanceApprovalsAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cachePendingLeave(supervisorId: String, leave: List<LeaveRequestDto>) =
        dao.upsert(CachedJsonEntity(supervisorId, KEY_PENDING_LEAVE, leaveAdapter.toJson(leave), System.currentTimeMillis()))

    suspend fun getCachedPendingLeave(supervisorId: String): List<LeaveRequestDto>? =
        dao.get(supervisorId, KEY_PENDING_LEAVE)?.let { runCatching { leaveAdapter.fromJson(it.json) }.getOrNull() }

    suspend fun cacheTeamRoster(supervisorId: String, team: List<NoMobileWorkerDto>) =
        dao.upsert(CachedJsonEntity(supervisorId, KEY_TEAM_ROSTER, noMobileWorkerAdapter.toJson(team), System.currentTimeMillis()))

    suspend fun getCachedTeamRoster(supervisorId: String): List<NoMobileWorkerDto>? =
        dao.get(supervisorId, KEY_TEAM_ROSTER)?.let { runCatching { noMobileWorkerAdapter.fromJson(it.json) }.getOrNull() }

    private companion object {
        const val KEY_SHIFTS = "shifts"
        const val KEY_COMPLETION_LOGS = "shift_completion_logs"
        const val KEY_LEAVE = "leave"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_PROFILE = "profile"
        const val KEY_ATTENDANCE_ROSTER = "supervisor_attendance_roster"
        const val KEY_SITES = "supervisor_sites"
        const val KEY_PENDING_APPROVALS = "supervisor_pending_approvals"
        const val KEY_PENDING_LEAVE = "supervisor_pending_leave"
        const val KEY_TEAM_ROSTER = "supervisor_team_roster"
    }
}
