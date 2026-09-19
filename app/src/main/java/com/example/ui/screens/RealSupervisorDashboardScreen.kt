package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.model.ShiftEventType
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NoMobileWorkerDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto
import com.example.security.SecureSessionStore
import com.example.ui.components.ArtifyTopHeader
import com.example.ui.components.CameraXSelfieDialog
import com.example.ui.components.ExportAttendancePdfDialog
import com.example.ui.components.ThemeSettingsDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.RealSupervisorUiState
import com.example.ui.viewmodel.RealSupervisorViewModel

private enum class SupTab { HOME, ROSTER, LEAVE, SITES, PROFILE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealSupervisorDashboardScreen(
    viewModel: RealSupervisorViewModel,
    supervisorName: String,
    supervisorCode: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(SupTab.HOME) }
    var showPendingApprovalsScreen by remember { mutableStateOf(false) }
    var rejectDialogFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // id, isAttendance
    var approveDialogForShift by remember { mutableStateOf<String?>(null) }
    var inspectShift by remember { mutableStateOf<AttendanceShiftDto?>(null) }
    var showAssignShiftDialog by remember { mutableStateOf(false) }
    var assignShiftWorker by remember { mutableStateOf<AttendanceShiftDto?>(null) }
    var showExportPdfDialog by remember { mutableStateOf(false) }
    var showProfileCameraDialog by remember { mutableStateOf(false) }
    // Home-tab attendance: holds the just-captured selfie file path while the "whose
    // attendance is this?" picker is shown, before the actual punch is submitted.
    var pendingAttendanceSelfiePath by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val supervisorPrefs = remember(supervisorCode) {
        context.getSharedPreferences("artify_supervisor_prefs", android.content.Context.MODE_PRIVATE)
    }
    val onboardingKey = "seen_supervisor_onboarding_${supervisorCode.ifBlank { "default" }}"
    var showOnboardingDialog by remember(supervisorCode) {
        mutableStateOf(!supervisorPrefs.getBoolean(onboardingKey, false))
    }

    val isDark = LocalIsDarkTheme.current
    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val navBg = if (isDark) SophisticatedDarkNav else SophisticatedLightNav

    if (showPendingApprovalsScreen) {
        SupervisorPendingApprovalsScreen(
            viewModel = viewModel,
            supervisorName = supervisorName,
            onBackClick = { showPendingApprovalsScreen = false }
        )
        return
    }

    val pendingOvertimeCount = remember(
        uiState.pendingAttendanceApprovals,
        uiState.pendingAttendance,
        uiState.attendanceRoster
    ) {
        val specificOvertime = uiState.pendingAttendanceApprovals.count {
            it.decision == "PENDING" && (
                it.complianceFlag?.contains("OVERTIME", ignoreCase = true) == true ||
                it.comment?.contains("overtime", ignoreCase = true) == true ||
                it.complianceFlag?.contains("UNCLOSED", ignoreCase = true) == true
            )
        } + uiState.pendingAttendance.count {
            it.complianceFlag.contains("OVERTIME", ignoreCase = true) ||
            it.reviewComment?.contains("overtime", ignoreCase = true) == true
        } + uiState.attendanceRoster.count {
            it.status == "OPEN" && (it.complianceFlag.contains("OVERTIME", ignoreCase = true) || (it.totalWorkedMinutes ?: 0) > 480)
        }

        if (specificOvertime > 0) {
            specificOvertime
        } else {
            val pendingApprovals = uiState.pendingAttendanceApprovals.count { it.decision == "PENDING" }
            if (pendingApprovals > 0) pendingApprovals else uiState.pendingAttendance.size
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = screenBg,
        topBar = {
            ArtifyTopHeader(
                userName = supervisorName,
                employeeId = supervisorCode,
                role = "SUPERVISOR",
                onLogoutClick = onLogout,
                notificationCount = uiState.pendingAttendance.size + uiState.pendingLeave.size
            )
        },
        bottomBar = {
            val navColors = @Composable {
                NavigationBarItemDefaults.colors(
                    selectedIconColor = SophisticatedPrimary,
                    indicatorColor = SophisticatedPrimaryContainer,
                    unselectedIconColor = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary,
                    unselectedTextColor = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary,
                    selectedTextColor = SophisticatedPrimary
                )
            }
            NavigationBar(
                containerColor = navBg,
                contentColor = SophisticatedPrimary,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = tab == SupTab.HOME,
                    onClick = { tab = SupTab.HOME },
                    icon = {
                        BadgedBox(badge = {
                            if (pendingOvertimeCount > 0) {
                                Badge(
                                    containerColor = SophisticatedWarning,
                                    contentColor = Color.White,
                                    modifier = Modifier.testTag("home_tab_overtime_badge")
                                ) {
                                    Text(
                                        text = if (pendingOvertimeCount > 99) "99+" else pendingOvertimeCount.toString(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }) { Icon(Icons.Default.Home, contentDescription = "Home") }
                    },
                    label = { Text("Home") },
                    modifier = Modifier.testTag("nav_home"),
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = tab == SupTab.ROSTER,
                    onClick = { tab = SupTab.ROSTER },
                    icon = { Icon(Icons.Default.People, contentDescription = "Roster") },
                    label = { Text("Roster") },
                    modifier = Modifier.testTag("nav_roster"),
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = tab == SupTab.LEAVE,
                    onClick = { tab = SupTab.LEAVE },
                    icon = {
                        BadgedBox(badge = {
                            if (uiState.pendingLeave.isNotEmpty()) {
                                Badge(containerColor = SophisticatedSecondary) {
                                    Text(uiState.pendingLeave.size.toString(), color = SophisticatedDarkBg)
                                }
                            }
                        }) { Icon(Icons.Default.EventAvailable, contentDescription = "Leave") }
                    },
                    label = { Text("Leave") },
                    modifier = Modifier.testTag("nav_sup_leave"),
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = tab == SupTab.SITES,
                    onClick = { tab = SupTab.SITES },
                    icon = { Icon(Icons.Default.LocationCity, contentDescription = "Sites") },
                    label = { Text("Sites") },
                    modifier = Modifier.testTag("nav_sites"),
                    colors = navColors()
                )
                NavigationBarItem(
                    selected = tab == SupTab.PROFILE,
                    onClick = { tab = SupTab.PROFILE },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                    label = { Text("Profile") },
                    modifier = Modifier.testTag("nav_profile"),
                    colors = navColors()
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(screenBg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (tab == SupTab.HOME) {
                    MetricsBar(uiState.metrics)
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        SupTab.HOME -> HomeTab(
                            uiState = uiState,
                            onOpenPendingApprovals = { showPendingApprovalsScreen = true },
                            onRequestAttendanceCamera = { viewModel.setAttendanceCameraDialog(true) }
                        )
                        SupTab.ROSTER -> RosterTab(
                            uiState = uiState,
                            onOpenAssignShift = { shift ->
                                assignShiftWorker = shift
                                showAssignShiftDialog = true
                            },
                            onOpenExportPdf = { showExportPdfDialog = true }
                        )
                        SupTab.LEAVE -> LeaveApprovalTab(
                            uiState,
                            onApprove = { viewModel.reviewLeave(it, true, null) },
                            onReject = { rejectDialogFor = it to false }
                        )
                        SupTab.SITES -> SitesTab(uiState.sites)
                        SupTab.PROFILE -> ProfileTab(
                            uiState = uiState,
                            fallbackName = supervisorName,
                            fallbackCode = supervisorCode,
                            onUpdatePhotoClick = { showProfileCameraDialog = true },
                            onLogout = onLogout
                        )
                    }
                }
            }

            uiState.statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedSuccessContainer,
                    border = BorderStroke(1.dp, SophisticatedSuccessBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            color = SophisticatedSuccess,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearFeedback() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SophisticatedSuccess)
                        }
                    }
                }
            }

            uiState.errorMessage?.let { err ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedErrorContainer,
                    border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            color = SophisticatedError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearFeedback() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SophisticatedError)
                        }
                    }
                }
            }
        }
    }

    if (showExportPdfDialog) {
        ExportAttendancePdfDialog(
            shifts = uiState.attendanceRoster,
            sites = uiState.sites,
            supervisorName = supervisorName,
            supervisorCode = supervisorCode,
            onDismiss = { showExportPdfDialog = false }
        )
    }

    if (showOnboardingDialog) {
        SupervisorOnboardingDialog(
            onDismiss = {
                supervisorPrefs.edit().putBoolean(onboardingKey, true).apply()
                showOnboardingDialog = false
            }
        )
    }

    val supervisorProjectName = uiState.profile?.projectName ?: uiState.myShift?.project?.name ?: "Assigned Site"
    val supervisorShiftOpen = uiState.myShift?.status == "OPEN"

    // Home tab attendance: capture the selfie FIRST (default framing is the supervisor's
    // own current shift state), THEN ask who it's for -- see AttendanceTargetPickerDialog
    // below, which resolves the actual clock-in/out action once a target is chosen.
    if (uiState.showAttendanceCameraDialog) {
        CameraXSelfieDialog(
            eventType = if (supervisorShiftOpen) ShiftEventType.END_SHIFT else ShiftEventType.START_SHIFT,
            projectName = supervisorProjectName,
            employeeName = supervisorName,
            onDismiss = { viewModel.setAttendanceCameraDialog(false) },
            onCaptureComplete = { path ->
                viewModel.setAttendanceCameraDialog(false)
                pendingAttendanceSelfiePath = path
            }
        )
    }
    pendingAttendanceSelfiePath?.let { path ->
        AttendanceTargetPickerDialog(
            supervisorName = supervisorName,
            supervisorShiftOpen = supervisorShiftOpen,
            teamRoster = uiState.teamRoster,
            onDismiss = {
                runCatching { java.io.File(path).delete() }
                pendingAttendanceSelfiePath = null
            },
            onConfirm = { targetId ->
                pendingAttendanceSelfiePath = null
                if (targetId == null) {
                    if (supervisorShiftOpen) viewModel.clockOutSelf(path) else viewModel.clockInSelf(path)
                } else {
                    val member = uiState.teamRoster.find { it.id == targetId }
                    if (member?.openShiftId != null) viewModel.proxyClockOut(targetId, path)
                    else viewModel.proxyClockIn(targetId, path)
                }
            }
        )
    }
    if (showProfileCameraDialog) {
        CameraXSelfieDialog(
            eventType = ShiftEventType.START_SHIFT,
            projectName = supervisorProjectName,
            employeeName = supervisorName,
            onDismiss = { showProfileCameraDialog = false },
            onCaptureComplete = { path ->
                showProfileCameraDialog = false
                viewModel.updateProfilePhoto(path)
            }
        )
    }

    rejectDialogFor?.let { (id, isAttendance) ->
        MandatoryReasonPrompt(
            title = if (isAttendance) "Reject Shift Attendance" else "Reject Leave Request",
            subtitle = if (isAttendance)
                "Document the mandatory reason for attendance rejection in the official audit record:"
            else
                "Document the official reason for leave rejection:",
            onDismiss = { rejectDialogFor = null },
            onConfirm = { reason ->
                if (isAttendance) viewModel.updateAttendanceApproval(id, false, reason, context, supervisorName)
                else viewModel.reviewLeave(id, false, reason)
                rejectDialogFor = null
            }
        )
    }
    approveDialogForShift?.let { shiftId ->
        ApproveCommentPrompt(
            onDismiss = { approveDialogForShift = null },
            onConfirm = { comment ->
                viewModel.updateAttendanceApproval(shiftId, true, comment, context, supervisorName)
                approveDialogForShift = null
            }
        )
    }
    inspectShift?.let { shift ->
        InspectAttendanceDialog(
            shift = shift,
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = { inspectShift = null },
            onApprove = {
                approveDialogForShift = shift.id
                inspectShift = null
            },
            onReject = {
                rejectDialogFor = shift.id to true
                inspectShift = null
            }
        )
    }

    if (showAssignShiftDialog) {
        val context = LocalContext.current
        // NOTE: previously fell back to fabricated names ("Hamad Al-Sabah", "Al-Ahmadi Refinery
        // Expansion", etc.) whenever the real roster/sites hadn't loaded yet, letting a supervisor
        // submit a real shift assignment against a fictional employee/site. Now these lists are
        // real-data-only or empty; AssignShiftScheduleDialog disables submission until real data exists.
        val availableWorkers: List<Pair<String, String>> = uiState.attendanceRoster.mapNotNull { it.employee }
            .filter { !it.employeeCode.isNullOrBlank() }
            .distinctBy { it.employeeCode }
            .map { it.employeeCode.orEmpty() to it.fullName }
        val availableSites = uiState.sites.map { it.name }

        AssignShiftScheduleDialog(
            initialWorker = assignShiftWorker,
            availableWorkers = availableWorkers,
            availableSites = availableSites,
            supervisorName = supervisorName,
            onDismiss = {
                showAssignShiftDialog = false
                assignShiftWorker = null
            },
            onConfirm = { empCode, empName, site, date, timing, isChange, reason, oldTime, notes ->
                viewModel.assignShiftAndNotifyEmployee(
                    context = context,
                    supervisorName = supervisorName,
                    employeeId = empCode,
                    employeeName = empName,
                    projectName = site,
                    shiftDate = date,
                    shiftTiming = timing,
                    notes = notes,
                    isScheduleChange = isChange,
                    changeReason = reason,
                    oldTiming = oldTime
                )
                showAssignShiftDialog = false
                assignShiftWorker = null
            }
        )
    }
}

@Composable
private fun MetricsBar(metrics: SupervisorMetricsDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SupervisorMiniStat("Present", metrics.present.toString(), "On Site", Icons.Default.CheckCircle, SophisticatedSuccess, Modifier.weight(1f))
        SupervisorMiniStat("Working", metrics.working.toString(), "Active", Icons.Default.Engineering, SophisticatedPrimary, Modifier.weight(1f))
        SupervisorMiniStat("Pending", (metrics.attendancePending + metrics.leavePending).toString(), "In Review", Icons.Default.HourglassEmpty, SophisticatedWarning, Modifier.weight(1f))
        SupervisorMiniStat("On Leave", metrics.onLeave.toString(), "Approved", Icons.Default.FlightTakeoff, SophisticatedSecondary, Modifier.weight(1f))
    }
}

@Composable
private fun SupervisorMiniStat(
    title: String,
    value: String,
    subtext: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 10.5.sp, color = textSecondary, fontWeight = FontWeight.Medium)
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Text(subtext, fontSize = 9.5.sp, color = textMuted)
        }
    }
}

@Composable
private fun MandatoryReasonPrompt(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val borderColor = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SophisticatedError
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory Rejection Reason *") },
                    placeholder = { Text("e.g. Geofence violation, invalid selfie...", color = textMuted) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textSecondary,
                        focusedBorderColor = SophisticatedError,
                        unfocusedBorderColor = borderColor,
                        focusedLabelColor = SophisticatedError,
                        unfocusedLabelColor = textSecondary,
                        cursorColor = SophisticatedError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mandatory_rejection_reason_input"),
                    minLines = 3
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textSecondary),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onConfirm(reason) },
                        enabled = reason.isNotBlank(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedError,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Confirm Rejection", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApproveCommentPrompt(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var comment by remember { mutableStateOf("Verified shift attendance and selfie evidence.") }
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val borderColor = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Approve Attendance",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Add an optional supervisor verification note:",
                    fontSize = 12.sp,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Supervisor Note") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textSecondary,
                        focusedBorderColor = SophisticatedPrimary,
                        unfocusedBorderColor = borderColor,
                        focusedLabelColor = SophisticatedPrimary,
                        unfocusedLabelColor = textSecondary,
                        cursorColor = SophisticatedPrimary
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textSecondary),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onConfirm(comment) },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedPrimary,
                            contentColor = SophisticatedOnPrimary
                        )
                    ) {
                        Text("Confirm Approval", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTab(
    uiState: RealSupervisorUiState,
    onOpenPendingApprovals: (() -> Unit)? = null,
    onRequestAttendanceCamera: () -> Unit = {}
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    val myShiftOpen = uiState.myShift?.status == "OPEN"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (onOpenPendingApprovals != null) {
            Surface(
                onClick = onOpenPendingApprovals,
                shape = RoundedCornerShape(12.dp),
                color = SophisticatedPrimary.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_pending_approvals_screen_btn")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PendingActions,
                            contentDescription = null,
                            tint = SophisticatedPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Pending Attendance Requests Queue",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = textPrimary
                                )
                                if (uiState.pendingAttendance.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = SophisticatedWarning
                                    ) {
                                        Text(
                                            text = uiState.pendingAttendance.size.toString(),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Open",
                        tint = SophisticatedPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Unified attendance card -- one selfie capture button, exactly like the Worker
        // dashboard's Home hero button. Who the punch is actually recorded for (the
        // supervisor themself, by default, or any employee on the same project) is
        // chosen AFTER the photo is taken -- see AttendanceTargetPickerDialog.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Attendance", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textPrimary)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (myShiftOpen) "You're clocked in since ${formatShiftTime(uiState.myShift?.clockIn?.serverTimestamp) ?: "—"}." else "Not currently clocked in.",
                    fontSize = 12.sp, color = textSecondary
                )
                Text(
                    "Take a selfie to record your own attendance, or on behalf of any team member on your project.",
                    fontSize = 11.sp, color = textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onRequestAttendanceCamera,
                    enabled = !uiState.isProcessing,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (myShiftOpen) SophisticatedError else SophisticatedPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (myShiftOpen) "End Shift with Selfie" else "Start Shift with Selfie", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AttendanceTargetPickerDialog(
    supervisorName: String,
    supervisorShiftOpen: Boolean,
    teamRoster: List<NoMobileWorkerDto>,
    onDismiss: () -> Unit,
    onConfirm: (targetEmployeeId: String?) -> Unit // null = the supervisor themself
) {
    var selectedId by remember { mutableStateOf<String?>(null) } // null = Myself
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val borderColor = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text("Whose Attendance Is This?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
                Text(
                    "Confirm who the selfie you just captured records attendance for.",
                    fontSize = 12.sp, color = textSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AttendanceTargetRow(
                        title = "Myself ($supervisorName)",
                        subtitle = if (supervisorShiftOpen) "Will clock out" else "Will clock in",
                        selected = selectedId == null,
                        onClick = { selectedId = null }
                    )
                    teamRoster.forEach { member ->
                        val isOpen = member.openShiftId != null
                        AttendanceTargetRow(
                            title = member.fullName,
                            subtitle = "${member.employeeCode} · ${member.role} · ${if (isOpen) "Will clock out" else "Will clock in"}",
                            selected = selectedId == member.id,
                            onClick = { selectedId = member.id }
                        )
                    }
                    if (teamRoster.isEmpty()) {
                        Text(
                            "No other employees found on your project.",
                            fontSize = 12.sp, color = textSecondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selectedId) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = Color.White)
                    ) {
                        Text("Confirm", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceTargetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) SophisticatedPrimary.copy(alpha = 0.15f) else cardBg,
        border = BorderStroke(1.dp, if (selected) SophisticatedPrimary else Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textPrimary)
                Text(subtitle, fontSize = 11.sp, color = textSecondary)
            }
            RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = SophisticatedPrimary))
        }
    }
}

@Composable
private fun AttendanceApprovalCard(
    shift: AttendanceShiftDto,
    onInspect: (AttendanceShiftDto) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_item_${shift.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = shift.employee?.fullName ?: "Unknown",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = textPrimary
                    )
                    Text(
                        text = "ID: ${shift.employee?.employeeCode ?: ""} • ${shift.employee?.role ?: ""}",
                        color = textMuted,
                        fontSize = 12.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SophisticatedWarningContainer,
                    border = BorderStroke(1.dp, SophisticatedWarning.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "PENDING",
                        color = SophisticatedWarning,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Site: ${shift.project?.name ?: "—"}",
                color = SophisticatedPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Text(
                text = "Shift Duration: ${formatShiftDurationHrsMins(shift.totalWorkedMinutes)} (${formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—"} to ${formatShiftTime(shift.clockOut?.serverTimestamp) ?: "—"})",
                color = textSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shift.clockIn?.selfieStoragePath != null || shift.clockOut?.selfieStoragePath != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SophisticatedSuccessContainer,
                        border = BorderStroke(1.dp, SophisticatedSuccessBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Selfie Verified", color = SophisticatedSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (shift.complianceFlag == "COMPLIANT") {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SophisticatedSuccessContainer,
                        border = BorderStroke(1.dp, SophisticatedSuccessBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Geofence Verified", color = SophisticatedSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SophisticatedErrorContainer,
                        border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                    ) {
                        Text(
                            shift.complianceFlag.replace('_', ' '),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SophisticatedError,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { onInspect(shift) },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textSecondary),
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inspect Evidence", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onReject(shift.id) },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                    border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                ) {
                    Text("Reject", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onApprove(shift.id) },
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("approve_btn_${shift.id}"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InspectAttendanceDialog(
    shift: AttendanceShiftDto,
    uiState: RealSupervisorUiState,
    viewModel: RealSupervisorViewModel,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val borderColor = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val bgBoxColor = if (isDark) SophisticatedDarkBg else SophisticatedLightBg

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Biometric Evidence Inspection",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = shift.employee?.fullName ?: "Unknown",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = textPrimary
                )
                Text(
                    text = "ID: ${shift.employee?.employeeCode ?: ""} • Role: ${shift.employee?.role ?: ""}",
                    color = textSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = "Site: ${shift.project?.name ?: ""}",
                    color = SophisticatedPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                val selfiePath = shift.clockIn?.selfieStoragePath ?: shift.clockOut?.selfieStoragePath
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgBoxColor)
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (selfiePath != null) {
                        LaunchedEffect(selfiePath) { viewModel.loadSelfieUrl(selfiePath) }
                        val url = uiState.selfieUrlCache[selfiePath]
                        if (url != null) {
                            AsyncImage(
                                model = url,
                                contentDescription = "Selfie evidence",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                                color = Color.Black.copy(alpha = 0.85f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("✓ Biometrically Verified & Stamped", color = SophisticatedSuccess, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text("Server: ${formatShiftTime(shift.clockIn?.serverTimestamp) ?: shift.shiftDate}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = SophisticatedPrimary)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Selfie Evidence Biometrically Stamped", color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Official Server Date: ${shift.shiftDate}", color = SophisticatedSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                InspectRow("Shift Date", formatDisplayDateDDMMYYYY(shift.shiftDate))
                InspectRow("Clock In", formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—")
                InspectRow("Clock Out", formatShiftTime(shift.clockOut?.serverTimestamp) ?: "In progress")
                InspectRow("Duration", formatShiftDurationHrsMins(shift.totalWorkedMinutes))
                InspectRow("Compliance", shift.complianceFlag.replace('_', ' '))

                Spacer(modifier = Modifier.height(18.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                        border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                    ) {
                        Text("Reject", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = onApprove,
                        modifier = Modifier
                            .weight(1.4f)
                            .height(46.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedPrimary,
                            contentColor = SophisticatedOnPrimary
                        )
                    ) {
                        Text("Approve & Sync ERP", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectRow(label: String, value: String) {
    val isDark = LocalIsDarkTheme.current
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textPrimary, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
    }
}

/** Formats a backend ISO-8601 timestamp (any offset) as a local HH:mm:ss clock time. */
private fun formatShiftTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = java.time.OffsetDateTime.parse(iso).toInstant()
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun LeaveApprovalTab(
    uiState: RealSupervisorUiState,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Employee Leave & Absence Review",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textPrimary
        )
        Text(
            text = "Approve or reject workforce absence requests with formal audit logs",
            fontSize = 12.sp,
            color = textSecondary
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (uiState.pendingLeave.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No pending leave requests.", fontSize = 13.sp, color = textSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.pendingLeave, key = { it.id }) { leave ->
                    LeaveApprovalCard(leave, onApprove, onReject)
                }
            }
        }
    }
}

@Composable
private fun LeaveApprovalCard(
    leave: LeaveRequestDto,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = leave.employee?.fullName ?: "Unknown",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = textPrimary
                    )
                    Text(
                        text = "ID: ${leave.employee?.employeeCode ?: "—"} • ${leave.leaveType} LEAVE",
                        fontSize = 12.sp,
                        color = SophisticatedPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when (leave.status) {
                        "APPROVED" -> SophisticatedSuccessContainer
                        "REJECTED" -> SophisticatedErrorContainer
                        else -> SophisticatedWarningContainer
                    }
                ) {
                    Text(
                        text = leave.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (leave.status) {
                            "APPROVED" -> SophisticatedSuccess
                            "REJECTED" -> SophisticatedError
                            else -> SophisticatedWarning
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Period: ${formatDisplayDateDDMMYYYY(leave.startDate)} to ${formatDisplayDateDDMMYYYY(leave.endDate)} (${leave.totalDays.toInt()} days)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = textPrimary
            )
            Text(
                text = "Reason: ${leave.reason}",
                fontSize = 12.sp,
                color = textSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { onReject(leave.id) },
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                    border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                ) {
                    Text("Reject", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onApprove(leave.id) },
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    )
                ) {
                    Text("Approve", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SitesTab(sites: List<SiteDto>) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Active Sites & Headcount Distribution",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textPrimary
        )
        Text(
            text = "Active project locations and live site rosters",
            fontSize = 12.sp,
            color = textSecondary
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (sites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No sites configured yet.", fontSize = 13.sp, color = textSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(sites, key = { it.id }) { site ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = site.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SophisticatedPrimaryContainer,
                                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "${site.employees?.size ?: 0} Workers",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SophisticatedPrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(site.address ?: "—", fontSize = 12.sp, color = textSecondary)
                            Text("Geofence: ${site.geofenceRadiusMeters.toInt()} m", fontSize = 11.sp, color = textMuted)
                            if (!site.employees.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Assigned Workers: ${site.employees.joinToString(", ")}",
                                    fontSize = 11.sp,
                                    color = textSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterTab(
    uiState: RealSupervisorUiState,
    onOpenAssignShift: (AttendanceShiftDto?) -> Unit,
    onOpenExportPdf: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    val filtered = uiState.attendanceRoster.filter { shift ->
        val matchesQuery = query.isBlank() ||
            (shift.employee?.fullName?.contains(query, true) == true) ||
            (shift.employee?.employeeCode?.contains(query, true) == true) ||
            (shift.project?.name?.contains(query, true) == true)
        val matchesStatus = statusFilter == "ALL" || shift.status == statusFilter
        matchesQuery && matchesStatus
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // FCM Push Dispatch Action Banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clickable { onOpenAssignShift(null) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SophisticatedPrimaryContainer),
            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(SophisticatedPrimary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = SophisticatedOnPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Push alerts to employees for shift assignments or schedule updates.",
                        fontSize = 12.sp,
                        color = textPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onOpenAssignShift(null) },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Send Alert", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "Workforce Attendance Roster",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textPrimary
        )
        Text(
            text = "${uiState.attendanceRoster.size} record(s) logged",
            fontSize = 11.sp,
            color = textSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name, ID, or site…", color = textMuted, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = textSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textPrimary,
                unfocusedTextColor = textSecondary,
                focusedBorderColor = SophisticatedPrimary,
                unfocusedBorderColor = cardBorder,
                cursorColor = SophisticatedPrimary
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL", "APPROVED", "PENDING_REVIEW", "REJECTED").forEach { f ->
                val isSel = statusFilter == f
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSel) SophisticatedPrimaryContainer else cardBg,
                    border = BorderStroke(1.dp, if (isSel) SophisticatedPrimary else cardBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { statusFilter = f }
                ) {
                    Text(
                        text = f.replace('_', ' '),
                        color = if (isSel) SophisticatedPrimary else textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching attendance records.",
                    fontSize = 12.sp,
                    color = textSecondary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { shift ->
                    AttendanceRosterCard(
                        shift = shift,
                        onAlertWorker = { onOpenAssignShift(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AttendanceRosterCard(
    shift: AttendanceShiftDto,
    onAlertWorker: (AttendanceShiftDto) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shift.employee?.fullName ?: "Unknown",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = textPrimary
                )
                Text(
                    text = "${shift.employee?.employeeCode ?: "—"} • ${shift.project?.name ?: "—"}",
                    fontSize = 11.sp,
                    color = textSecondary
                )
                Text(
                    text = "${shift.shiftDate} • ${shift.totalWorkedMinutes ?: 0} mins",
                    fontSize = 11.sp,
                    color = textMuted
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(shift.status)
                OutlinedButton(
                    onClick = { onAlertWorker(shift) },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedPrimary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------- Profile tab ----------------
// Modeled directly on the Worker dashboard's own ProfileTab (RealWorkerDashboardScreen.kt)
// so a supervisor's profile screen looks and behaves the same way.

@Composable
private fun ProfileTab(
    uiState: RealSupervisorUiState,
    fallbackName: String,
    fallbackCode: String,
    onUpdatePhotoClick: () -> Unit = {},
    onLogout: () -> Unit
) {
    val profile = uiState.profile
    val context = LocalContext.current
    val themePreferences = remember { ThemePreferences.getInstance(context) }
    val themeSettings by themePreferences.settings.collectAsState()
    val isSystemDark = LocalIsDarkTheme.current
    var showThemeDialog by remember { mutableStateOf(false) }
    val deviceId = remember { SecureSessionStore.getInstance(context).deviceId }

    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Profile Header Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_header_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Avatar with Initials & Camera Badge
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(SophisticatedPrimary, SophisticatedTertiary)
                                )
                            )
                            .border(2.5.dp, SophisticatedPrimary.copy(alpha = 0.8f), CircleShape)
                            .clickable { onUpdatePhotoClick() }
                            .testTag("user_avatar_image")
                    ) {
                        if (uiState.localAvatarPath != null) {
                            AsyncImage(
                                model = java.io.File(uiState.localAvatarPath),
                                contentDescription = "Profile Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initials = (profile?.fullName ?: fallbackName)
                                .split(" ")
                                .mapNotNull { it.firstOrNull()?.toString() }
                                .take(2)
                                .joinToString("")
                                .ifEmpty { "SV" }
                            Text(
                                text = initials,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Camera Edit FAB Badge
                    Surface(
                        shape = CircleShape,
                        color = SophisticatedPrimary,
                        shadowElevation = 4.dp,
                        border = BorderStroke(2.dp, cardBg),
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { onUpdatePhotoClick() }
                            .testTag("change_profile_photo_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Update Profile Photo",
                                tint = SophisticatedOnPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Full Name
                Text(
                    text = profile?.fullName ?: fallbackName,
                    color = textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Role Pill Badge
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer,
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SophisticatedPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${profile?.role ?: "SUPERVISOR"} • ${profile?.department ?: "—"}",
                            color = SophisticatedPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "ID: ${profile?.employeeCode ?: fallbackCode} • ${profile?.companyName ?: "—"}",
                    color = textSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Update Profile Photo Button (Camera)
                OutlinedButton(
                    onClick = onUpdatePhotoClick,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SophisticatedPrimary.copy(alpha = 0.08f),
                        contentColor = SophisticatedPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("update_profile_pic_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Update Profile Photo (Camera)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Display & Environment Theme Settings Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("theme_settings_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DISPLAY & ENVIRONMENT THEME",
                        color = SophisticatedPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer
                    ) {
                        Text(
                            text = if (isSystemDark) "DARK MODE" else "LIGHT MODE",
                            color = SophisticatedPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "High-contrast dynamic themes designed for bright daylight and night construction visibility.",
                    color = textSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3-Way Mode Segmented Selector (System, Light, Dark)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeMode.values().forEach { mode ->
                        val isSelected = themeSettings.themeMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { themePreferences.setThemeMode(mode) }
                                .testTag("theme_mode_chip_${mode.name.lowercase()}"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) SophisticatedPrimary else Color.Transparent,
                            tonalElevation = if (isSelected) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when (mode) {
                                        ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) SophisticatedOnPrimary else textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = mode.shortName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) SophisticatedOnPrimary else textSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Persistent Dark Mode Switch Row
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDark) SophisticatedDarkSurfaceHigh.copy(alpha = 0.5f) else SophisticatedLightSurfaceHigh.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isSystemDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = SophisticatedPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Dark Theme",
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when (themeSettings.themeMode) {
                                        ThemeMode.DARK -> "Always Dark (Battery & Night Shift)"
                                        ThemeMode.LIGHT -> "Always Light (High Daylight Visibility)"
                                        ThemeMode.SYSTEM -> "Following System Mode"
                                    },
                                    color = textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Switch(
                            checked = isSystemDark,
                            onCheckedChange = { checked ->
                                themePreferences.setThemeMode(if (checked) ThemeMode.DARK else ThemeMode.LIGHT)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SophisticatedPrimary,
                                checkedTrackColor = SophisticatedPrimaryContainer,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.testTag("dark_mode_toggle_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Button to open Full Theme & Dynamic Color Settings
                OutlinedButton(
                    onClick = { showThemeDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customize_theme_btn"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SophisticatedPrimary
                    ),
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configure Dynamic Colors & System Themes",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Contact & Credentials ---
        ProfileSectionLabel("CONTACT & CREDENTIALS")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                ProfileDetailRow("Email", profile?.email ?: "—")
                ProfileDetailRow("Phone", profile?.phone ?: "—")
                ProfileDetailRow("Department", profile?.department ?: "—")
                ProfileDetailRow("Assigned Site", profile?.projectName ?: "—")
                ProfileDetailRow("Account Status", if (profile != null) "Active" else "—")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        ProfileSectionLabel("SECURITY & DEVICE")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                ProfileDetailRow("Device ID", deviceId.take(18) + "…")
                ProfileDetailRow("Facial Biometrics", "Enrolled & Active")
                ProfileDetailRow("Time Authority", "Authoritative server clock (UTC)")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(
            onClick = onLogout,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isDark) SophisticatedErrorContainer.copy(alpha = 0.2f) else SophisticatedLightErrorContainer.copy(alpha = 0.2f),
                contentColor = SophisticatedError
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showThemeDialog) {
        ThemeSettingsDialog(themePreferences = themePreferences, onDismiss = { showThemeDialog = false })
    }
}

@Composable
private fun ProfileDetailRow(label: String, value: String) {
    val isDark = LocalIsDarkTheme.current
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = textSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
    }
}

@Composable
private fun ProfileSectionLabel(text: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    Text(
        text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
        color = SophisticatedPrimary, modifier = modifier.padding(bottom = 6.dp)
    )
}

@Composable
fun AssignShiftScheduleDialog(
    initialWorker: AttendanceShiftDto? = null,
    availableWorkers: List<Pair<String, String>>,
    availableSites: List<String>,
    supervisorName: String,
    onDismiss: () -> Unit,
    onConfirm: (
        employeeCode: String,
        employeeName: String,
        siteName: String,
        shiftDate: String,
        shiftTiming: String,
        isScheduleChange: Boolean,
        changeReason: String?,
        oldTiming: String?,
        notes: String?
    ) -> Unit
) {
    var isScheduleChange by remember { mutableStateOf(false) }
    // No fabricated identity here: an empty string means "nothing real to select yet", and the
    // Send button below is disabled until a real employee and site are chosen.
    var selectedEmployeeCode by remember {
        mutableStateOf(initialWorker?.employee?.employeeCode ?: availableWorkers.firstOrNull()?.first ?: "")
    }
    var selectedEmployeeName by remember {
        mutableStateOf(initialWorker?.employee?.fullName ?: availableWorkers.firstOrNull()?.second ?: "")
    }
    var selectedSite by remember {
        mutableStateOf(initialWorker?.project?.name ?: availableSites.firstOrNull() ?: "")
    }
    var shiftDate by remember {
        mutableStateOf(java.time.LocalDate.now().plusDays(1).toString())
    }
    var shiftTiming by remember { mutableStateOf("Morning (07:00 - 15:30)") }
    var oldTiming by remember { mutableStateOf("Morning (07:00 - 15:30)") }
    var changeReason by remember { mutableStateOf("Site Operations Reschedule") }
    var specialNotes by remember { mutableStateOf("Please report to site gate with standard PPE.") }

    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val borderColor = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SophisticatedPrimaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isScheduleChange) Icons.Default.Update else Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = SophisticatedPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isScheduleChange) "Schedule Change Alert" else "New Shift Assignment",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = textPrimary
                            )
                            Text(
                                text = "Firebase Cloud Messaging Dispatch",
                                fontSize = 11.sp,
                                color = SophisticatedPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Toggle: New Shift vs Schedule Change
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isDark) Color(0xFF1E2638) else Color(0xFFE8EEF5), RoundedCornerShape(12.dp))
                        .padding(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isScheduleChange) SophisticatedPrimary else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isScheduleChange = false }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.WorkHistory,
                                contentDescription = null,
                                tint = if (!isScheduleChange) SophisticatedOnPrimary else textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "New Shift",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isScheduleChange) SophisticatedOnPrimary else textSecondary
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isScheduleChange) SophisticatedWarning else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isScheduleChange = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.EditCalendar,
                                contentDescription = null,
                                tint = if (isScheduleChange) Color.Black else textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Schedule Change",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isScheduleChange) Color.Black else textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Target Employee
                Text(
                    text = "Assignee Employee",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (availableWorkers.isEmpty()) {
                    Text(
                        text = "No roster loaded yet — wait for sync or refresh before assigning a shift.",
                        fontSize = 11.sp,
                        color = SophisticatedWarning
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableWorkers.forEach { (code, name) ->
                        val isSelected = selectedEmployeeCode == code
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) SophisticatedPrimaryContainer else if (isDark) Color(0xFF1E2638) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else borderColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable {
                                    selectedEmployeeCode = code
                                    selectedEmployeeName = name
                                }
                        ) {
                            Text(
                                text = "$name ($code)",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) SophisticatedPrimary else textPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Project / Site
                Text(
                    text = "Work Site",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (availableSites.isEmpty()) {
                    Text(
                        text = "No sites loaded yet — wait for sync or refresh before assigning a shift.",
                        fontSize = 11.sp,
                        color = SophisticatedWarning
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availableSites.forEach { site ->
                        val isSelected = selectedSite == site
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) SophisticatedPrimaryContainer else if (isDark) Color(0xFF1E2638) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else borderColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { selectedSite = site }
                        ) {
                            Text(
                                text = site,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) SophisticatedPrimary else textPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Shift Date
                Text(
                    text = "Shift Date",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val today = java.time.LocalDate.now().toString()
                    val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
                    val dayAfter = java.time.LocalDate.now().plusDays(2).toString()
                    listOf("Today ($today)" to today, "Tomorrow ($tomorrow)" to tomorrow, "Day After ($dayAfter)" to dayAfter).forEach { (label, dateVal) ->
                        val isSelected = shiftDate == dateVal
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) SophisticatedPrimaryContainer else if (isDark) Color(0xFF1E2638) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else borderColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { shiftDate = dateVal }
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) SophisticatedPrimary else textPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Shift Timing
                Text(
                    text = if (isScheduleChange) "New Shift Hours" else "Shift Hours",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                val timingPresets = listOf(
                    "Morning (07:00 - 15:30)",
                    "Evening (15:30 - 23:30)",
                    "Night (23:30 - 07:30)",
                    "Split Shift (08:00 - 12:00 & 16:00 - 20:00)"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    timingPresets.forEach { timing ->
                        val isSelected = shiftTiming == timing
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) SophisticatedPrimaryContainer else if (isDark) Color(0xFF1E2638) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else borderColor),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { shiftTiming = timing }
                        ) {
                            Text(
                                text = timing,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) SophisticatedPrimary else textPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (isScheduleChange) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Original / Previous Timing",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = oldTiming,
                        onValueChange = { oldTiming = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textSecondary,
                            focusedBorderColor = SophisticatedPrimary,
                            unfocusedBorderColor = borderColor
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Reason for Schedule Adjustment",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val reasonPresets = listOf(
                        "Site Operations Reschedule",
                        "High Temperature Protocol",
                        "Urgent Shift Coverage",
                        "Project Delivery Acceleration"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        reasonPresets.forEach { reason ->
                            val isSelected = changeReason == reason
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (isSelected) SophisticatedWarningContainer else if (isDark) Color(0xFF1E2638) else Color(0xFFF1F5F9),
                                border = BorderStroke(1.dp, if (isSelected) SophisticatedWarning else borderColor),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { changeReason = reason }
                            ) {
                                Text(
                                    text = reason,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) SophisticatedWarning else textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = changeReason,
                        onValueChange = { changeReason = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textSecondary,
                            focusedBorderColor = SophisticatedPrimary,
                            unfocusedBorderColor = borderColor
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Notes / Instructions
                Text(
                    text = "Special Instructions / Safety Notes",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = specialNotes,
                    onValueChange = { specialNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textSecondary,
                        focusedBorderColor = SophisticatedPrimary,
                        unfocusedBorderColor = borderColor
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // FCM Cloud Notification Banner Indicator
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedPrimaryContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = SophisticatedPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Firebase Cloud Messaging Dispatch",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = SophisticatedPrimary
                            )
                            Text(
                                text = "Topic: employee_${selectedEmployeeCode} & shifts_schedules • High Priority",
                                fontSize = 10.sp,
                                color = textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = textSecondary),
                        border = BorderStroke(1.dp, borderColor),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onConfirm(
                                selectedEmployeeCode,
                                selectedEmployeeName,
                                selectedSite,
                                shiftDate,
                                shiftTiming,
                                isScheduleChange,
                                if (isScheduleChange) changeReason else null,
                                if (isScheduleChange) oldTiming else null,
                                specialNotes
                            )
                        },
                        // Never submit a shift assignment against an unselected (fabricated) employee/site.
                        enabled = selectedEmployeeCode.isNotBlank() && selectedSite.isNotBlank(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1.6f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isScheduleChange) SophisticatedWarning else SophisticatedPrimary,
                            contentColor = if (isScheduleChange) Color.Black else SophisticatedOnPrimary
                        )
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isScheduleChange) "Dispatch Change Alert" else "Dispatch Shift Alert",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupervisorOnboardingDialog(
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .testTag("supervisor_onboarding_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with Badge & Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(SophisticatedPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SophisticatedPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Supervisor Quick Guide",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = textPrimary
                        )
                        Text(
                            text = "Essential tools to manage your team",
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Card 1: How to initiate a shift
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SophisticatedPrimary.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SophisticatedPrimary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = SophisticatedPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "1. How to Initiate a Shift",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Open the Roster tab to assign shifts or notify workers. On-site workers also initiate shifts by scanning a biometric verification selfie at their assigned geofenced project location.",
                                fontSize = 12.sp,
                                color = textSecondary,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Card 2: Where to find Pending Attendance Request queue
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SophisticatedWarning.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, SophisticatedWarning.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SophisticatedWarning.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PendingActions,
                                contentDescription = null,
                                tint = SophisticatedWarning,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "2. Pending Attendance Queue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Located at the bottom of your Home screen. Tap 'Pending Attendance Requests Queue' to review submitted shift verifications, check audit photos, and approve overtime requests.",
                                fontSize = 12.sp,
                                color = textSecondary,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dismiss / Action Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("dismiss_onboarding_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    )
                ) {
                    Text(
                        text = "Got It, Let's Begin",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
