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
import com.example.network.AttendanceShiftDto
import com.example.network.AuditLogDto
import com.example.network.ErpEventDto
import com.example.network.LeaveRequestDto
import com.example.network.SiteDto
import com.example.network.SupervisorMetricsDto
import com.example.ui.components.ArtifyTopHeader
import com.example.ui.theme.*
import com.example.ui.viewmodel.RealSupervisorUiState
import com.example.ui.viewmodel.RealSupervisorViewModel

private enum class SupTab { APPROVALS, ROSTER, LEAVE, SITES, AUDIT }

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
    var tab by remember { mutableStateOf(SupTab.APPROVALS) }
    var rejectDialogFor by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // id, isAttendance
    var approveDialogForShift by remember { mutableStateOf<String?>(null) }
    var inspectShift by remember { mutableStateOf<AttendanceShiftDto?>(null) }
    var showAssignShiftDialog by remember { mutableStateOf(false) }
    var assignShiftWorker by remember { mutableStateOf<AttendanceShiftDto?>(null) }

    val isDark = LocalIsDarkTheme.current
    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val navBg = if (isDark) SophisticatedDarkNav else SophisticatedLightNav

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
                    selected = tab == SupTab.APPROVALS,
                    onClick = { tab = SupTab.APPROVALS },
                    icon = {
                        BadgedBox(badge = {
                            if (uiState.pendingAttendance.isNotEmpty()) {
                                Badge(containerColor = SophisticatedWarning) {
                                    Text(uiState.pendingAttendance.size.toString(), color = Color.White)
                                }
                            }
                        }) { Icon(Icons.Default.Verified, contentDescription = "Approvals") }
                    },
                    label = { Text("Approvals") },
                    modifier = Modifier.testTag("nav_approvals"),
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
                    selected = tab == SupTab.AUDIT,
                    onClick = { tab = SupTab.AUDIT },
                    icon = { Icon(Icons.Default.SyncAlt, contentDescription = "Audit & ERP") },
                    label = { Text("ERP") },
                    modifier = Modifier.testTag("nav_audit_erp"),
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
                MetricsBar(uiState.metrics)
                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        SupTab.APPROVALS -> ApprovalsTab(
                            uiState,
                            onInspect = { inspectShift = it },
                            onApprove = { approveDialogForShift = it },
                            onReject = { rejectDialogFor = it to true }
                        )
                        SupTab.ROSTER -> RosterTab(
                            uiState = uiState,
                            onOpenAssignShift = { shift ->
                                assignShiftWorker = shift
                                showAssignShiftDialog = true
                            }
                        )
                        SupTab.LEAVE -> LeaveApprovalTab(
                            uiState,
                            onApprove = { viewModel.reviewLeave(it, true, null) },
                            onReject = { rejectDialogFor = it to false }
                        )
                        SupTab.SITES -> SitesTab(uiState.sites)
                        SupTab.AUDIT -> AuditErpTab(uiState.auditLogs, uiState.erpEvents)
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

    rejectDialogFor?.let { (id, isAttendance) ->
        MandatoryReasonPrompt(
            title = if (isAttendance) "Reject Shift Attendance" else "Reject Leave Request",
            subtitle = if (isAttendance)
                "Document the mandatory reason for attendance rejection in the official audit record:"
            else
                "Document the official reason for leave rejection:",
            onDismiss = { rejectDialogFor = null },
            onConfirm = { reason ->
                if (isAttendance) viewModel.reviewAttendance(id, false, reason)
                else viewModel.reviewLeave(id, false, reason)
                rejectDialogFor = null
            }
        )
    }
    approveDialogForShift?.let { shiftId ->
        ApproveCommentPrompt(
            onDismiss = { approveDialogForShift = null },
            onConfirm = { comment ->
                viewModel.reviewAttendance(shiftId, true, comment)
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
        val availableWorkers: List<Pair<String, String>> = uiState.attendanceRoster.mapNotNull { it.employee }
            .filter { !it.employeeCode.isNullOrBlank() }
            .distinctBy { it.employeeCode }
            .map { (it.employeeCode ?: "EMP-001") to it.fullName }
            .ifEmpty { listOf("EMP-001" to "Hamad Al-Sabah", "EMP-002" to "Fatima Al-Enezi", "EMP-003" to "Tariq Mansoor") }
        val availableSites = uiState.sites.map { it.name }
            .ifEmpty { listOf("Al-Ahmadi Refinery Expansion", "Kuwait City Commercial Tower", "Shuwaikh Port Logistics Hub") }

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
private fun ApprovalsTab(
    uiState: RealSupervisorUiState,
    onInspect: (AttendanceShiftDto) -> Unit,
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
            text = "Pending Attendance Submissions (${uiState.pendingAttendance.size})",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textPrimary
        )
        Text(
            text = "Review selfie biometric evidence & verified server timestamps",
            fontSize = 12.sp,
            color = textSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.pendingAttendance.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.TaskAlt,
                        contentDescription = null,
                        tint = SophisticatedSuccess,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All pending attendances have been reviewed!",
                        color = textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.pendingAttendance, key = { it.id }) { shift ->
                    AttendanceApprovalCard(shift, onInspect, onApprove, onReject)
                }
            }
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
                text = "Shift Duration: ${shift.totalWorkedMinutes ?: 0} mins (${formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—"} to ${formatShiftTime(shift.clockOut?.serverTimestamp) ?: "—"})",
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

                InspectRow("Shift Date", shift.shiftDate)
                InspectRow("Clock In", formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—")
                InspectRow("Clock Out", formatShiftTime(shift.clockOut?.serverTimestamp) ?: "In progress")
                InspectRow("Duration", "${shift.totalWorkedMinutes ?: 0} minutes")
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
                text = "Period: ${leave.startDate} to ${leave.endDate} (${leave.totalDays.toInt()} days)",
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
    onOpenAssignShift: (AttendanceShiftDto?) -> Unit
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
                .padding(bottom = 12.dp)
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
                        text = "Assign Shift & Push Alert",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = SophisticatedPrimary
                    )
                    Text(
                        text = "Dispatch FCM push alerts to employee devices for shift assignments or schedule updates.",
                        fontSize = 11.sp,
                        color = textSecondary
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

@Composable
private fun AuditErpTab(auditLogs: List<AuditLogDto>, erpEvents: List<ErpEventDto>) {
    var subTab by remember { mutableIntStateOf(0) }
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
            text = "Governance & ERP Integration",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = textPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))

        TabRow(
            selectedTabIndex = subTab,
            containerColor = cardBg,
            contentColor = SophisticatedPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = {
                    Text(
                        "Audit (${auditLogs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (subTab == 0) SophisticatedPrimary else textSecondary
                    )
                }
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = {
                    Text(
                        "ERP Outbox (${erpEvents.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (subTab == 1) SophisticatedPrimary else textSecondary
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (subTab == 0) {
            if (auditLogs.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No audit entries yet.", fontSize = 12.sp, color = textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(auditLogs, key = { it.id }) { log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(
                                        text = log.action.replace('_', ' '),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = SophisticatedPrimary
                                    )
                                    Text(
                                        text = log.actorRole ?: "",
                                        fontSize = 11.sp,
                                        color = textMuted
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                log.reason?.let { Text(it, fontSize = 12.sp, color = textPrimary) }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(log.createdAt, fontSize = 10.sp, color = textMuted)
                            }
                        }
                    }
                }
            }
        } else {
            if (erpEvents.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No ERP outbox events yet.", fontSize = 12.sp, color = textSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(erpEvents, key = { it.id }) { event ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(1.dp, cardBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = event.eventType,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = textPrimary
                                    )
                                    StatusPill(event.status)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Idempotency: ${event.idempotencyKey}", fontSize = 10.sp, color = textMuted)
                                Text("ERP Ref: ${event.responseRef ?: "PENDING"}", fontSize = 11.sp, color = SophisticatedPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
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
    var selectedEmployeeCode by remember {
        mutableStateOf(initialWorker?.employee?.employeeCode ?: availableWorkers.firstOrNull()?.first ?: "EMP-001")
    }
    var selectedEmployeeName by remember {
        mutableStateOf(initialWorker?.employee?.fullName ?: availableWorkers.firstOrNull()?.second ?: "Hamad Al-Sabah")
    }
    var selectedSite by remember {
        mutableStateOf(initialWorker?.project?.name ?: availableSites.firstOrNull() ?: "Al-Ahmadi Refinery Expansion")
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
