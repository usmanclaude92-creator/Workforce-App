package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.AttendanceApprovalDto
import com.example.ui.theme.*
import com.example.ui.viewmodel.RealSupervisorViewModel

private data class ApprovalBadgeStyle(
    val bgColor: Color,
    val borderColor: Color,
    val textColor: Color,
    val displayLabel: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Visual badge displaying the attendance approval status ('Pending', 'Approved', 'Rejected').
 */
@Composable
fun AttendanceApprovalStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val normalized = status.uppercase().trim()
    val badgeStyle = when {
        normalized == "APPROVED" -> ApprovalBadgeStyle(
            if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer,
            SophisticatedSuccessBorder,
            if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
            "Approved",
            Icons.Default.CheckCircle
        )
        normalized == "REJECTED" -> ApprovalBadgeStyle(
            if (isDark) SophisticatedErrorContainer else SophisticatedLightErrorContainer,
            if (isDark) SophisticatedError.copy(alpha = 0.4f) else SophisticatedLightError.copy(alpha = 0.4f),
            if (isDark) SophisticatedError else SophisticatedLightError,
            "Rejected",
            Icons.Default.Cancel
        )
        else -> ApprovalBadgeStyle(
            if (isDark) SophisticatedWarningContainer else SophisticatedLightWarningContainer,
            SophisticatedWarning.copy(alpha = 0.45f),
            if (isDark) SophisticatedWarning else SophisticatedLightWarning,
            "Pending",
            Icons.Default.HourglassEmpty
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = badgeStyle.bgColor,
        border = BorderStroke(1.dp, badgeStyle.borderColor),
        modifier = modifier.testTag("attendance_approval_badge_${badgeStyle.displayLabel.lowercase()}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = badgeStyle.icon,
                contentDescription = badgeStyle.displayLabel,
                tint = badgeStyle.textColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = badgeStyle.displayLabel,
                color = badgeStyle.textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Screen displaying pending attendance requests queried from the 'attendance_approvals' table.
 * Allows logged-in supervisors to approve or reject attendance records with real-time FCM notification dispatch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorPendingApprovalsScreen(
    viewModel: RealSupervisorViewModel,
    supervisorName: String,
    onBackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isDark = LocalIsDarkTheme.current

    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder

    var searchQuery by remember { mutableStateOf("") }
    var approveDialogItem by remember { mutableStateOf<AttendanceApprovalDto?>(null) }
    var rejectDialogItem by remember { mutableStateOf<AttendanceApprovalDto?>(null) }
    var approveComment by remember { mutableStateOf("") }
    var rejectReason by remember { mutableStateOf("") }
    var isRejectError by remember { mutableStateOf(false) }

    // Initial load from attendance_approvals table
    LaunchedEffect(Unit) {
        viewModel.loadPendingAttendanceApprovals()
    }

    // Combine approvals from attendance_approvals table with any unreviewed pending attendance shifts
    val pendingList = remember(uiState.pendingAttendanceApprovals, uiState.pendingAttendance) {
        if (uiState.pendingAttendanceApprovals.isNotEmpty()) {
            uiState.pendingAttendanceApprovals
        } else {
            // Map any pending shifts into approval DTOs if table is freshly migrating
            uiState.pendingAttendance.map { shift ->
                AttendanceApprovalDto(
                    id = "APPR-${shift.id.takeLast(8)}",
                    shiftId = shift.id,
                    employeeId = shift.employeeId,
                    projectId = shift.projectId,
                    decision = "PENDING",
                    comment = shift.reviewComment,
                    reviewedAt = shift.reviewedAt,
                    createdAt = shift.clockIn?.serverTimestamp,
                    employeeName = shift.employee?.fullName ?: "Employee",
                    employeeCode = shift.employee?.employeeCode ?: shift.employeeId,
                    projectName = shift.project?.name ?: "Assigned Project Site",
                    shiftDate = shift.shiftDate,
                    clockInTime = shift.clockIn?.serverTimestamp,
                    clockOutTime = shift.clockOut?.serverTimestamp,
                    totalWorkedMinutes = shift.totalWorkedMinutes,
                    complianceFlag = shift.complianceFlag,
                    selfieUrl = shift.clockOut?.selfieStoragePath ?: shift.clockIn?.selfieStoragePath
                )
            }
        }
    }

    val filteredList = remember(pendingList, searchQuery) {
        if (searchQuery.isBlank()) {
            pendingList
        } else {
            val q = searchQuery.trim().lowercase()
            pendingList.filter { item ->
                (item.employeeName?.lowercase()?.contains(q) == true) ||
                (item.employeeCode?.lowercase()?.contains(q) == true) ||
                (item.projectName?.lowercase()?.contains(q) == true) ||
                (item.shiftDate?.lowercase()?.contains(q) == true)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("supervisor_pending_approvals_screen"),
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Pending Attendance Requests",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            text = "attendance_approvals workflow",
                            fontSize = 11.5.sp,
                            color = textSecondary
                        )
                    }
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.testTag("back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = textPrimary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.loadPendingAttendanceApprovals()
                            viewModel.refresh()
                        },
                        modifier = Modifier.testTag("refresh_approvals_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SophisticatedPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = screenBg
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Status bar count chip & explanation
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(SophisticatedWarning.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PendingActions,
                                contentDescription = null,
                                tint = SophisticatedWarning,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Awaiting Supervisor Decision",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimary
                            )
                            Text(
                                text = "Decisions update the ledger and trigger push alerts",
                                fontSize = 11.sp,
                                color = textSecondary
                            )
                        }
                    }
                    Badge(
                        containerColor = if (pendingList.isNotEmpty()) SophisticatedWarning else SophisticatedSuccess,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = "${pendingList.size} Pending",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_pending_approvals_input"),
                placeholder = { Text("Search by worker name, ID, or site...", fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SophisticatedPrimary,
                    unfocusedBorderColor = cardBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Content List
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = SophisticatedSuccess,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No matching attendance requests" else "All Attendance Requests Reviewed",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "Try changing your search terms" else "The 'attendance_approvals' table has 0 pending items.",
                            fontSize = 12.5.sp,
                            color = textSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pending_approvals_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredList, key = { it.id.ifEmpty { it.shiftId } }) { item ->
                        PendingAttendanceApprovalCard(
                            item = item,
                            onApprove = {
                                approveComment = ""
                                approveDialogItem = item
                            },
                            onReject = {
                                rejectReason = ""
                                isRejectError = false
                                rejectDialogItem = item
                            }
                        )
                    }
                }
            }
        }
    }

    // Approve Confirmation Dialog
    approveDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { approveDialogItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = SophisticatedSuccess,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Approve Attendance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = textPrimary
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Approve shift attendance for ${item.employeeName ?: "Worker"} on ${item.shiftDate ?: "this shift"}?",
                        fontSize = 13.5.sp,
                        color = textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = approveComment,
                        onValueChange = { approveComment = it },
                        label = { Text("Note / Comment (Optional)") },
                        placeholder = { Text("e.g. Verified on site") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("approve_comment_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val comment = approveComment.trim().ifEmpty { null }
                        viewModel.updateAttendanceApproval(
                            shiftId = item.shiftId,
                            approve = true,
                            comment = comment,
                            context = context,
                            supervisorName = supervisorName
                        )
                        approveDialogItem = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedSuccess
                    ),
                    modifier = Modifier.testTag("confirm_approve_button")
                ) {
                    Text("Confirm Approval", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { approveDialogItem = null },
                    modifier = Modifier.testTag("cancel_approve_button")
                ) {
                    Text("Cancel", color = textSecondary)
                }
            }
        )
    }

    // Reject Confirmation Dialog (Mandatory Reason)
    rejectDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { rejectDialogItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = SophisticatedError,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reject Attendance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = textPrimary
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Provide a rejection reason for ${item.employeeName ?: "Worker"}'s attendance. An alert will be sent via push notification.",
                        fontSize = 13.sp,
                        color = textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = {
                            rejectReason = it
                            if (it.isNotBlank()) isRejectError = false
                        },
                        label = { Text("Rejection Reason *") },
                        placeholder = { Text("e.g. Unverified location, incomplete shift") },
                        isError = isRejectError,
                        supportingText = {
                            if (isRejectError) {
                                Text("A rejection reason is mandatory.", color = SophisticatedError)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reject_reason_input"),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (rejectReason.trim().isBlank()) {
                            isRejectError = true
                        } else {
                            viewModel.updateAttendanceApproval(
                                shiftId = item.shiftId,
                                approve = false,
                                comment = rejectReason.trim(),
                                context = context,
                                supervisorName = supervisorName
                            )
                            rejectDialogItem = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedError
                    ),
                    modifier = Modifier.testTag("confirm_reject_button")
                ) {
                    Text("Confirm Rejection", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { rejectDialogItem = null },
                    modifier = Modifier.testTag("cancel_reject_button")
                ) {
                    Text("Cancel", color = textSecondary)
                }
            }
        )
    }
}

/**
 * Card representing a single pending attendance request from the attendance_approvals table.
 */
@Composable
private fun PendingAttendanceApprovalCard(
    item: AttendanceApprovalDto,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val surfaceHigh = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pending_approval_card_${item.shiftId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Row: Worker info & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (item.employeeName?.take(1) ?: "W").uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = SophisticatedPrimary,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = item.employeeName ?: "Worker",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "ID: ${item.employeeCode ?: "—"} • ${item.projectName ?: "Site"}",
                            fontSize = 11.5.sp,
                            color = textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Visual Badge ('Pending', 'Approved', 'Rejected')
                AttendanceApprovalStatusBadge(status = item.decision)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timings breakdown
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = surfaceHigh,
                border = BorderStroke(1.dp, cardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DATE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        Text(item.shiftDate ?: "Today", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                    }
                    Column {
                        Text("CLOCK IN", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        Text(formatTimeDisplay(item.clockInTime), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                    }
                    Column {
                        Text("CLOCK OUT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        Text(formatTimeDisplay(item.clockOutTime), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = textPrimary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("DURATION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                        val mins = item.totalWorkedMinutes ?: 0
                        val hrs = mins / 60
                        val remMins = mins % 60
                        Text("${hrs}h ${remMins}m", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SophisticatedPrimary)
                    }
                }
            }

            // Compliance indicator if present
            if (item.complianceFlag != null && item.complianceFlag != "OK" && item.complianceFlag != "VERIFIED") {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SophisticatedWarning.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, SophisticatedWarning.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = SophisticatedWarning,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Flag: ${item.complianceFlag}",
                            fontSize = 11.sp,
                            color = SophisticatedWarning,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Approve & Reject
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Reject Button
                OutlinedButton(
                    onClick = onReject,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SophisticatedError
                    ),
                    border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("reject_btn_${item.shiftId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Reject",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                }

                // Approve Button
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedSuccess
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("approve_btn_${item.shiftId}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Approve",
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Approve", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }
    }
}

private fun formatTimeDisplay(isoString: String?): String {
    if (isoString.isNullOrBlank()) return "—"
    return try {
        if (isoString.contains("T")) {
            val timePart = isoString.substringAfter("T").substringBefore(".")
            timePart.take(5) // HH:mm
        } else {
            isoString.takeLast(8).take(5)
        }
    } catch (_: Exception) {
        isoString.take(5)
    }
}
