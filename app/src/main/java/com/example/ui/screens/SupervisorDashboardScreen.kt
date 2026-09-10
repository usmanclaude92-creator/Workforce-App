package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.entity.AttendanceEntity
import com.example.data.entity.AuditLogEntity
import com.example.data.entity.LeaveRequestEntity
import com.example.data.entity.UserEntity
import com.example.model.AttendanceState
import com.example.model.LeaveStatus
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.SupervisorViewModel
import coil.compose.AsyncImage
import java.io.File

@Composable
fun SupervisorDashboardScreen(
    supervisorViewModel: SupervisorViewModel,
    onLogoutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by supervisorViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Approvals, 1=Roster, 2=Leave, 3=Sites, 4=Audit&ERP

    val isDark = LocalIsDarkTheme.current
    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val navBg = if (isDark) SophisticatedDarkNav else SophisticatedLightNav

    var showRejectDialogForAttendance by remember { mutableStateOf<String?>(null) }
    var showRejectDialogForLeave by remember { mutableStateOf<String?>(null) }
    var approveComment by remember { mutableStateOf("") }
    var showApproveCommentDialogForAttendance by remember { mutableStateOf<String?>(null) }
    var showExportPdfDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ArtifyTopHeader(
                userName = uiState.supervisorUser?.fullName ?: "Supervisor",
                employeeId = uiState.supervisorUser?.employeeId ?: "SUP-01",
                role = "SUPERVISOR",
                avatarUrl = uiState.supervisorUser?.avatarUrl,
                onLogoutClick = onLogoutClick,
                notificationCount = uiState.pendingApprovals.size
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = navBg,
                contentColor = MaterialTheme.colorScheme.primary,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.pendingApprovals.isNotEmpty()) {
                                    Badge(containerColor = SophisticatedWarning) {
                                        Text(uiState.pendingApprovals.size.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Verified, contentDescription = "Approvals")
                        }
                    },
                    label = { Text("Approvals") },
                    modifier = Modifier.testTag("nav_approvals"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.People, contentDescription = "Roster") },
                    label = { Text("Roster") },
                    modifier = Modifier.testTag("nav_roster"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.pendingLeaveRequests.isNotEmpty()) {
                                    Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                                        Text(uiState.pendingLeaveRequests.size.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.EventAvailable, contentDescription = "Leave")
                        }
                    },
                    label = { Text("Leave") },
                    modifier = Modifier.testTag("nav_sup_leave"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.LocationCity, contentDescription = "Sites") },
                    label = { Text("Sites") },
                    modifier = Modifier.testTag("nav_sites"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.SyncAlt, contentDescription = "Audit & ERP") },
                    label = { Text("ERP") },
                    modifier = Modifier.testTag("nav_audit_erp"),
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(screenBg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Supervisor Metrics Overview Bar
                SupervisorMetricsBar(metrics = uiState.metrics)

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> PendingApprovalsView(
                            pendingList = uiState.pendingApprovals,
                            onInspect = { supervisorViewModel.selectAttendanceForReview(it) },
                            onApprove = { showApproveCommentDialogForAttendance = it.attendanceId },
                            onReject = { showRejectDialogForAttendance = it.attendanceId }
                        )
                        1 -> AttendanceRosterView(
                            allAttendance = uiState.allAttendance,
                            onInspect = { supervisorViewModel.selectAttendanceForReview(it) },
                            onExportPdf = { showExportPdfDialog = true }
                        )
                        2 -> LeaveApprovalView(
                            pendingLeaves = uiState.pendingLeaveRequests,
                            allLeaves = uiState.allLeaveRequests,
                            onApprove = { supervisorViewModel.approveLeave(it.requestId) },
                            onReject = { showRejectDialogForLeave = it.requestId }
                        )
                        3 -> SitesHeadcountView(
                            projects = uiState.allProjects,
                            employees = uiState.allEmployees,
                            allAttendance = uiState.allAttendance
                        )
                        4 -> AuditAndErpView(
                            auditLogs = uiState.auditLogs,
                            erpEvents = uiState.erpOutboxEvents,
                            isOnline = uiState.isNetworkOnline,
                            isSyncing = uiState.isSyncingFirestore,
                            queuedCount = uiState.queuedFirestoreCount,
                            lastSyncTimestamp = uiState.lastFirestoreSyncTime,
                            syncLogs = uiState.firestoreSyncLogs,
                            onManualSync = { supervisorViewModel.triggerManualSync() },
                            onToggleNetwork = { supervisorViewModel.simulateNetwork(!uiState.isNetworkOnline) }
                        )
                    }
                }
            }

            // Snackbar feedback
            uiState.statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedSuccessContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedSuccessBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = msg, color = SophisticatedSuccess, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { supervisorViewModel.clearFeedback() }) {
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = err, color = SophisticatedError, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { supervisorViewModel.clearFeedback() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SophisticatedError)
                        }
                    }
                }
            }
        }

        // --- Dialogs ---

        uiState.selectedAttendance?.let { attendance ->
            SupervisorAttendanceInspectDialog(
                attendance = attendance,
                onDismiss = { supervisorViewModel.selectAttendanceForReview(null) },
                onApprove = {
                    supervisorViewModel.approveAttendance(attendance.attendanceId, "Approved after evidence inspection")
                },
                onReject = {
                    showRejectDialogForAttendance = attendance.attendanceId
                }
            )
        }

        showApproveCommentDialogForAttendance?.let { attendanceId ->
            ApproveCommentDialog(
                onDismiss = { showApproveCommentDialogForAttendance = null },
                onConfirm = { comment ->
                    supervisorViewModel.approveAttendance(attendanceId, comment)
                    showApproveCommentDialogForAttendance = null
                }
            )
        }

        showRejectDialogForAttendance?.let { attendanceId ->
            MandatoryReasonDialog(
                title = "Reject Shift Attendance",
                subtitle = "Document the mandatory reason for attendance rejection in the official audit record:",
                onDismiss = { showRejectDialogForAttendance = null },
                onConfirm = { reason ->
                    supervisorViewModel.rejectAttendance(attendanceId, reason)
                    showRejectDialogForAttendance = null
                }
            )
        }

        showRejectDialogForLeave?.let { leaveId ->
            MandatoryReasonDialog(
                title = "Reject Leave Request",
                subtitle = "Document the official reason for leave rejection:",
                onDismiss = { showRejectDialogForLeave = null },
                onConfirm = { reason ->
                    supervisorViewModel.rejectLeave(leaveId, reason)
                    showRejectDialogForLeave = null
                }
            )
        }

        if (showExportPdfDialog) {
            ExportAttendancePdfDialog(
                attendanceList = uiState.allAttendance,
                projects = uiState.allProjects,
                supervisorUser = uiState.supervisorUser,
                onDismiss = { showExportPdfDialog = false }
            )
        }
    }
}

@Composable
private fun SupervisorMetricsBar(metrics: com.example.ui.viewmodel.SupervisorMetrics) {
    val isDark = LocalIsDarkTheme.current
    val barSurface = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val barBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = barSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, barBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricChip(label = "Present", count = metrics.presentCount, color = SophisticatedSuccess)
            MetricChip(label = "Working", count = metrics.currentlyWorkingCount, color = SophisticatedPrimary)
            MetricChip(label = "Pending", count = metrics.pendingApprovalCount, color = SophisticatedWarning)
            MetricChip(label = "On Leave", count = metrics.onLeaveCount, color = SophisticatedSecondary)
            MetricChip(label = "Late", count = metrics.lateCount, color = SophisticatedWarning)
            MetricChip(label = "Absent", count = metrics.absentCount, color = SophisticatedError)
        }
    }
}

@Composable
private fun MetricChip(label: String, count: Int, color: Color) {
    val isDark = LocalIsDarkTheme.current
    val labelColor = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PendingApprovalsView(
    pendingList: List<AttendanceEntity>,
    onInspect: (AttendanceEntity) -> Unit,
    onApprove: (AttendanceEntity) -> Unit,
    onReject: (AttendanceEntity) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Pending Attendance Submissions (${pendingList.size})",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Text(
            text = "Review selfie biometric evidence & verified server timestamps",
            fontSize = 12.sp,
            color = textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (pendingList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.TaskAlt, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(52.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("All pending attendances have been reviewed!", color = textPrimary, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pendingList) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pending_item_${item.attendanceId}"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = item.employeeName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = "ID: ${item.employeeId} • ${item.employeeRole}",
                                        color = textSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SophisticatedWarningContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedWarning.copy(alpha = 0.4f))
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
                                text = "Site: ${item.projectName}",
                                color = SophisticatedPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Shift Duration: ${item.totalWorkedMinutes} mins (${item.startTimeFormatted} to ${item.endTimeFormatted})",
                                color = SophisticatedTextSecondary,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Geofence & Selfie Evidence indicators
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SophisticatedSuccessContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedSuccessBorder)
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
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SophisticatedSuccessContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedSuccessBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Geofence Verified", color = SuccessGreen600, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { onInspect(item) },
                                    modifier = Modifier.height(40.dp),
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedTextSecondary),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Inspect Evidence", fontSize = 12.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { onReject(item) },
                                    modifier = Modifier.height(40.dp),
                                    shape = RoundedCornerShape(50),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                                ) {
                                    Text("Reject", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onApprove(item) },
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("approve_btn_${item.attendanceId}"),
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
            }
        }
    }
}

@Composable
private fun AttendanceRosterView(
    allAttendance: List<AttendanceEntity>,
    onInspect: (AttendanceEntity) -> Unit,
    onExportPdf: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredList = allAttendance.filter {
        (selectedFilter == "ALL" || it.state == selectedFilter) &&
                (it.employeeName.contains(searchQuery, ignoreCase = true) ||
                        it.employeeId.contains(searchQuery, ignoreCase = true) ||
                        it.projectName.contains(searchQuery, ignoreCase = true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Workforce Attendance Roster",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SophisticatedTextPrimary
                )
                Text(
                    text = "${allAttendance.size} records logged",
                    fontSize = 11.sp,
                    color = SophisticatedTextSecondary
                )
            }

            Button(
                onClick = onExportPdf,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SophisticatedPrimary,
                    contentColor = SophisticatedOnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("export_pdf_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by name, ID, or site...", color = SophisticatedTextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SophisticatedTextSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SophisticatedTextPrimary,
                unfocusedTextColor = SophisticatedTextSecondary,
                focusedBorderColor = SophisticatedPrimary,
                unfocusedBorderColor = SophisticatedDarkBorder,
                cursorColor = SophisticatedPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Status Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL", "APPROVED", "PENDING_APPROVAL", "REJECTED").forEach { f ->
                val isSel = selectedFilter == f
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSel) SophisticatedPrimaryContainer else SophisticatedDarkSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSel) SophisticatedPrimary else SophisticatedDarkBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { selectedFilter = f }
                ) {
                    Text(
                        text = f.replace("_", " "),
                        color = if (isSel) SophisticatedPrimary else SophisticatedTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredList) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInspect(item) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.employeeName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SophisticatedTextPrimary)
                            Text(text = "${item.employeeId} • ${item.projectName}", color = SophisticatedTextSecondary, fontSize = 11.sp)
                            Text(text = "${item.shiftDate} • ${item.totalWorkedMinutes} mins", color = SophisticatedTextMuted, fontSize = 11.sp)
                        }
                        AttendanceStatusBadge(state = item.state)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaveApprovalView(
    pendingLeaves: List<LeaveRequestEntity>,
    allLeaves: List<LeaveRequestEntity>,
    onApprove: (LeaveRequestEntity) -> Unit,
    onReject: (LeaveRequestEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Employee Leave & Absence Review",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SophisticatedTextPrimary
        )
        Text(
            text = "Approve or reject workforce absence requests with formal audit logs",
            fontSize = 12.sp,
            color = SophisticatedTextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (allLeaves.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No leave requests submitted", color = SophisticatedTextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(allLeaves) { leave ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(text = leave.employeeName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SophisticatedTextPrimary)
                                    Text(text = "ID: ${leave.employeeId} • ${leave.type.replace("_", " ")}", color = SophisticatedPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                                        color = when (leave.status) {
                                            "APPROVED" -> SophisticatedSuccess
                                            "REJECTED" -> SophisticatedError
                                            else -> SophisticatedWarning
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Period: ${leave.startDate} to ${leave.endDate} (${leave.totalDays} days)",
                                color = SophisticatedTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Reason: ${leave.reason}",
                                color = SophisticatedTextSecondary,
                                fontSize = 12.sp
                            )

                            if (leave.status == LeaveStatus.PENDING.name) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    OutlinedButton(
                                        onClick = { onReject(leave) },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
                                    ) {
                                        Text("Reject", fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = { onApprove(leave) },
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(50),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SophisticatedPrimary,
                                            contentColor = SophisticatedOnPrimary
                                        )
                                    ) {
                                        Text("Approve", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SitesHeadcountView(
    projects: List<com.example.data.entity.ProjectEntity>,
    employees: List<UserEntity>,
    allAttendance: List<AttendanceEntity>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Active Sites & Headcount Distribution",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SophisticatedTextPrimary
        )
        Text(
            text = "Active project locations and live site rosters",
            fontSize = 12.sp,
            color = SophisticatedTextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(projects) { p ->
                val siteEmployees = employees.filter { it.assignedProjectId == p.projectId }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = p.projectName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = SophisticatedTextPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = SophisticatedPrimaryContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "${siteEmployees.size} Workers",
                                    color = SophisticatedPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(text = p.address, color = SophisticatedTextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Assigned Workers: " + siteEmployees.joinToString(", ") { it.fullName },
                            color = SophisticatedTextSecondary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditAndErpView(
    auditLogs: List<AuditLogEntity>,
    erpEvents: List<com.example.data.entity.ErpOutboxEntity>,
    isOnline: Boolean = true,
    isSyncing: Boolean = false,
    queuedCount: Int = 0,
    lastSyncTimestamp: Long? = null,
    syncLogs: List<com.example.sync.SyncLogItem> = emptyList(),
    onManualSync: () -> Unit = {},
    onToggleNetwork: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Governance & ERP Integration",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SophisticatedTextPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SophisticatedDarkSurface,
            contentColor = SophisticatedPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, SophisticatedDarkBorder, RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "Audit Trail (${auditLogs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (selectedTab == 0) SophisticatedPrimary else SophisticatedTextSecondary
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        "ERP Outbox (${erpEvents.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (selectedTab == 1) SophisticatedPrimary else SophisticatedTextSecondary
                    )
                }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Text(
                        "Cloud Sync (${queuedCount})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (selectedTab == 2) SophisticatedPrimary else if (queuedCount > 0) SophisticatedWarning else SophisticatedTextSecondary
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(auditLogs) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = log.action.replace("_", " "),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = SophisticatedPrimary
                                )
                                Text(
                                    text = "Actor: ${log.actorName} (${log.actorRole})",
                                    fontSize = 11.sp,
                                    color = SophisticatedTextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = log.details, color = SophisticatedTextPrimary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "UTC Server Timestamp: ${log.serverTimestampUtc}",
                                color = SophisticatedTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        } else if (selectedTab == 1) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(erpEvents) { event ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = event.eventType,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = SophisticatedSuccess
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SophisticatedSuccessContainer
                                ) {
                                    Text(
                                        text = event.status,
                                        color = SophisticatedSuccess,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = "Idempotency Key: ${event.idempotencyKey}", color = SophisticatedTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "Payload: ${event.payloadJson}", color = SophisticatedTextSecondary, fontSize = 11.sp)
                            Text(text = "ERP Ref: ${event.erpResponseRef ?: "PENDING"}", color = SophisticatedPrimary, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                        border = BorderStroke(1.dp, if (!isOnline) SophisticatedWarning.copy(alpha = 0.5f) else SophisticatedSuccess.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (!isOnline) Icons.Default.CloudOff else if (isSyncing) Icons.Default.Sync else Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = if (!isOnline) SophisticatedWarning else if (isSyncing) SophisticatedPrimary else SophisticatedSuccess,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Firestore Cloud Persistence", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SophisticatedTextPrimary)
                                }

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (isOnline) SophisticatedSuccessContainer else SophisticatedWarningContainer
                                ) {
                                    Text(
                                        text = if (isOnline) "ONLINE" else "OFFLINE",
                                        color = if (isOnline) SophisticatedSuccess else SophisticatedWarning,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Persistent disk cache with automatic reconnection reconciliation. Clock-ins queued offline automatically sync to the server when connection is re-established.",
                                fontSize = 11.sp,
                                color = SophisticatedTextSecondary,
                                lineHeight = 15.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Queued Local Clock-Ins:", fontSize = 11.sp, color = SophisticatedTextMuted)
                                Text(
                                    text = if (queuedCount == 0) "0 (All Synced)" else "$queuedCount Pending",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (queuedCount > 0) SophisticatedWarning else SophisticatedSuccess
                                )
                            }

                            if (lastSyncTimestamp != null && lastSyncTimestamp > 0) {
                                val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastSyncTimestamp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Last Sync Completed:", fontSize = 11.sp, color = SophisticatedTextMuted)
                                    Text(timeStr + " UTC", fontSize = 10.sp, color = SophisticatedTextPrimary)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onManualSync,
                                    enabled = isOnline && !isSyncing,
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = SophisticatedDarkBg)
                                ) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SophisticatedDarkBg)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Syncing...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Sync Now", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                OutlinedButton(
                                    onClick = onToggleNetwork,
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isOnline) SophisticatedWarning.copy(alpha = 0.6f) else SophisticatedSuccess.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isOnline) SophisticatedWarning else SophisticatedSuccess)
                                ) {
                                    Icon(
                                        imageVector = if (isOnline) Icons.Default.WifiOff else Icons.Default.Wifi,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isOnline) "Simulate Offline" else "Restore Online", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Sync Telemetry Activity Log", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = SophisticatedTextPrimary)
                }

                if (syncLogs.isEmpty()) {
                    item {
                        Text("No sync events recorded yet.", fontSize = 11.sp, color = SophisticatedTextMuted)
                    }
                } else {
                    items(syncLogs) { log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
                            border = BorderStroke(1.dp, SophisticatedDarkBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = log.message, fontSize = 11.sp, color = SophisticatedTextPrimary, fontWeight = FontWeight.Medium)
                                    Text(text = "${log.formattedTime} UTC", fontSize = 9.sp, color = SophisticatedTextMuted)
                                }
                                Text(
                                    text = log.status.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (log.status) {
                                        com.example.sync.SyncStatus.SYNCED -> SophisticatedSuccess
                                        com.example.sync.SyncStatus.PENDING -> SophisticatedWarning
                                        com.example.sync.SyncStatus.FAILED -> SophisticatedError
                                    }
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
private fun SupervisorAttendanceInspectDialog(
    attendance: AttendanceEntity,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
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
                        color = SophisticatedTextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = SophisticatedTextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Employee Overview
                Text(text = attendance.employeeName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = SophisticatedTextPrimary)
                Text(text = "ID: ${attendance.employeeId} • Role: ${attendance.employeeRole}", color = SophisticatedTextSecondary, fontSize = 12.sp)
                Text(text = "Site: ${attendance.projectName}", color = SophisticatedPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                Spacer(modifier = Modifier.height(14.dp))

                // Selfie Evidence Visual Box
                val selfiePath = attendance.startSelfieData ?: attendance.endSelfieData
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SophisticatedDarkBg)
                        .border(1.dp, SophisticatedDarkBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val selfieModel: Any? = when {
                        selfiePath.isNullOrBlank() -> null
                        selfiePath.startsWith("http://") || selfiePath.startsWith("https://") -> selfiePath
                        selfiePath.startsWith("attendance-selfies/") -> "${com.example.network.ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/public/$selfiePath"
                        File(selfiePath).exists() -> File(selfiePath)
                        else -> null
                    }
                    if (selfieModel != null) {
                        AsyncImage(
                            model = selfieModel,
                            contentDescription = "Employee Biometric Selfie Evidence",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        // Floating Biometric Timestamp Overlay
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
                                Text("Server: ${attendance.startTimeFormatted}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Selfie Evidence Biometrically Stamped", color = SophisticatedTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Hash: ${selfiePath ?: "VERIFIED"}", color = SophisticatedTextMuted, fontSize = 10.sp)
                            Text("Official Server Stamped: ${attendance.startTimeFormatted}", color = SophisticatedSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                DetailRow("Shift Date", attendance.shiftDate)
                DetailRow("Start Server Time", attendance.startTimeFormatted ?: "None")
                DetailRow("End Server Time", attendance.endTimeFormatted ?: "None")
                DetailRow("Total Duration", "${attendance.totalWorkedMinutes} minutes")
                DetailRow("Biometric Verification", "Selfie Face Match Verified")
                DetailRow("Hardware Device ID", attendance.deviceId)

                Spacer(modifier = Modifier.height(18.dp))

                if (attendance.state == AttendanceState.PENDING_APPROVAL.name) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onReject,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedError),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f))
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
                } else {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedPrimary,
                            contentColor = SophisticatedOnPrimary
                        )
                    ) {
                        Text("Close Inspection")
                    }
                }
            }
        }
    }
}

@Composable
private fun ApproveCommentDialog(
    onDismiss: () -> Unit,
    onConfirm: (comment: String) -> Unit
) {
    var comment by remember { mutableStateOf("Verified shift attendance and selfie evidence.") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Approve Attendance",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SophisticatedTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Add an optional supervisor verification note:",
                    fontSize = 12.sp,
                    color = SophisticatedTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Supervisor Note") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SophisticatedTextPrimary,
                        unfocusedTextColor = SophisticatedTextSecondary,
                        focusedBorderColor = SophisticatedPrimary,
                        unfocusedBorderColor = SophisticatedDarkBorder,
                        focusedLabelColor = SophisticatedPrimary,
                        unfocusedLabelColor = SophisticatedTextSecondary,
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
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
                        Text("Confirm Approval")
                    }
                }
            }
        }
    }
}

@Composable
private fun MandatoryReasonDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
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
                    color = SophisticatedTextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Mandatory Rejection Reason *") },
                    placeholder = { Text("e.g. Geofence violation, invalid selfie...", color = SophisticatedTextMuted) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SophisticatedTextPrimary,
                        unfocusedTextColor = SophisticatedTextSecondary,
                        focusedBorderColor = SophisticatedError,
                        unfocusedBorderColor = SophisticatedDarkBorder,
                        focusedLabelColor = SophisticatedError,
                        unfocusedLabelColor = SophisticatedTextSecondary,
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
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SophisticatedTextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onConfirm(reason) },
                        enabled = reason.isNotBlank(),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("confirm_rejection_btn"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedError,
                            contentColor = Color(0xFF601410)
                        )
                    ) {
                        Text("Confirm Rejection")
                    }
                }
            }
        }
    }
}
