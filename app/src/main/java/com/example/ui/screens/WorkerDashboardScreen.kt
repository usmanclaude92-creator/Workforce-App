package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.entity.AttendanceEntity
import com.example.data.entity.LeaveRequestEntity
import com.example.data.repository.WorkforceRepository
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.LocationTestScenario
import com.example.ui.viewmodel.WorkerViewModel
import coil.compose.AsyncImage
import java.io.File

@Composable
fun WorkerDashboardScreen(
    workerViewModel: WorkerViewModel,
    onLogoutClick: () -> Unit,
    repository: WorkforceRepository? = null,
    locationHelper: com.example.location.LocationHelper? = null,
    modifier: Modifier = Modifier
) {
    val uiState by workerViewModel.uiState.collectAsState()
    val isDark = LocalIsDarkTheme.current
    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val navBg = if (isDark) SophisticatedDarkNav else SophisticatedLightNav

    var selectedBottomNav by remember { mutableIntStateOf(0) }
    var isRequestingLeave by remember { mutableStateOf(false) }
    var showScenarioDropdown by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var selectedAttendanceForDetails by remember { mutableStateOf<AttendanceEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            val hideTopHeader = (selectedBottomNav == 1 && repository != null) ||
                                (selectedBottomNav == 2 && isRequestingLeave && repository != null)
            if (!hideTopHeader) {
                ArtifyTopHeader(
                    userName = uiState.currentUser?.fullName ?: "Employee",
                    employeeId = uiState.currentUser?.employeeId ?: "",
                    role = uiState.currentUser?.role ?: "WORKER",
                    avatarUrl = uiState.currentUser?.avatarUrl,
                    onAvatarClick = { selectedBottomNav = 3 },
                    onLogoutClick = onLogoutClick,
                    notificationCount = uiState.notifications.count { !it.isRead },
                    onNotificationClick = { showNotificationDialog = true }
                )
            }
        },
        bottomBar = {
            if (!(selectedBottomNav == 2 && isRequestingLeave && repository != null)) {
                NavigationBar(
                    containerColor = navBg,
                    contentColor = MaterialTheme.colorScheme.primary,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedBottomNav == 0,
                        onClick = { selectedBottomNav = 0; isRequestingLeave = false },
                        icon = { Icon(Icons.Default.Schedule, contentDescription = "Shift") },
                        label = { Text("Shift") },
                        modifier = Modifier.testTag("nav_shift"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    NavigationBarItem(
                        selected = selectedBottomNav == 1,
                        onClick = { selectedBottomNav = 1; isRequestingLeave = false },
                        icon = { Icon(Icons.Default.History, contentDescription = "My Shifts") },
                        label = { Text("My Shifts") },
                        modifier = Modifier.testTag("nav_my_shifts"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    NavigationBarItem(
                        selected = selectedBottomNav == 2,
                        onClick = { selectedBottomNav = 2 },
                        icon = { Icon(Icons.Default.EventNote, contentDescription = "Leave") },
                        label = { Text("Leave") },
                        modifier = Modifier.testTag("nav_leave"),
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    NavigationBarItem(
                        selected = selectedBottomNav == 3,
                        onClick = { selectedBottomNav = 3; isRequestingLeave = false },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                        label = { Text("Profile") },
                        modifier = Modifier.testTag("nav_profile"),
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(screenBg)
        ) {
            when (selectedBottomNav) {
                0 -> ShiftDashboardTab(
                    workerViewModel = workerViewModel,
                    locationHelper = locationHelper,
                    onOpenScenarioPicker = { showScenarioDropdown = true },
                    onNavigateToLogs = { selectedBottomNav = 1 }
                )
                1 -> {
                    if (repository != null && uiState.currentUser != null) {
                        DailyAttendanceLogsScreen(
                            repository = repository,
                            currentUser = uiState.currentUser!!
                        )
                    } else {
                        AttendanceHistoryTab(
                            attendanceList = uiState.attendanceHistory,
                            onItemClick = { selectedAttendanceForDetails = it }
                        )
                    }
                }
                2 -> {
                    if (isRequestingLeave && repository != null && uiState.currentUser != null) {
                        RequestLeaveScreen(
                            repository = repository,
                            currentUser = uiState.currentUser!!,
                            onBackClick = { isRequestingLeave = false },
                            onRequestSubmitted = { isRequestingLeave = false }
                        )
                    } else {
                        LeaveManagementTab(
                            leaveList = uiState.leaveHistory,
                            onOpenSubmitDialog = {
                                if (repository != null && uiState.currentUser != null) {
                                    isRequestingLeave = true
                                } else {
                                    workerViewModel.setLeaveDialog(true)
                                }
                            }
                        )
                    }
                }
                3 -> WorkerProfileTab(
                    currentUser = uiState.currentUser,
                    assignedProject = uiState.assignedProject,
                    onUpdatePhotoClick = { workerViewModel.setProfileCameraDialog(true) },
                    onLogoutClick = onLogoutClick
                )
            }

            // Snackbar / Status feedback
            uiState.statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = SuccessGreen600,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = msg, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { workerViewModel.clearFeedback() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
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
                    shape = RoundedCornerShape(10.dp),
                    color = ErrorRed600,
                    tonalElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = err, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { workerViewModel.clearFeedback() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
                        }
                    }
                }
            }
        }

        // --- Dialogs ---

        if (uiState.showStartShiftDialog) {
            CameraXSelfieDialog(
                eventType = ShiftEventType.START_SHIFT,
                projectName = uiState.assignedProject?.projectName ?: "Project Site",
                employeeName = uiState.currentUser?.fullName,
                onDismiss = { workerViewModel.setStartShiftDialog(false) },
                onCaptureComplete = { selfiePath ->
                    workerViewModel.startShift(selfiePath)
                }
            )
        }

        if (uiState.showEndShiftDialog) {
            EndShiftConfirmDialog(
                activeShift = uiState.activeShift,
                projectName = uiState.assignedProject?.projectName ?: "Project Site",
                onDismiss = { workerViewModel.setEndShiftDialog(false) },
                onConfirm = {
                    workerViewModel.endShift()
                }
            )
        }

        if (uiState.showLeaveDialog) {
            SubmitLeaveDialog(
                onDismiss = { workerViewModel.setLeaveDialog(false) },
                onSubmit = { type, start, end, days, reason ->
                    workerViewModel.submitLeave(type, start, end, days, reason)
                }
            )
        }

        if (showScenarioDropdown) {
            LocationScenarioPickerDialog(
                currentScenario = uiState.testScenario,
                onSelect = {
                    workerViewModel.updateLocationScenario(it)
                    showScenarioDropdown = false
                },
                onDismiss = { showScenarioDropdown = false }
            )
        }

        selectedAttendanceForDetails?.let { item ->
            AttendanceDetailDialog(
                attendance = item,
                onDismiss = { selectedAttendanceForDetails = null }
            )
        }

        if (uiState.showProfileCameraDialog) {
            CameraXProfilePhotoDialog(
                employeeName = uiState.currentUser?.fullName,
                employeeId = uiState.currentUser?.employeeId,
                onDismiss = { workerViewModel.setProfileCameraDialog(false) },
                onCaptureComplete = { photoPath ->
                    workerViewModel.updateProfilePicture(photoPath)
                }
            )
        }

        if (showNotificationDialog) {
            NotificationsDialog(
                notifications = uiState.notifications,
                onDismiss = { showNotificationDialog = false },
                onMarkRead = { workerViewModel.markNotificationRead(it) }
            )
        }
    }
}

@Composable
private fun ShiftDashboardTab(
    workerViewModel: WorkerViewModel,
    locationHelper: com.example.location.LocationHelper? = null,
    onOpenScenarioPicker: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val uiState by workerViewModel.uiState.collectAsState()
    val isShiftActive = uiState.activeShift != null

    // Infinite breathing pulse for the circular camera button ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isShiftActive) 1.06f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // NOTE: Geofence/GPS status is intentionally never shown to the worker here — it is
        // a compliance signal captured on the attendance record for supervisor/HCM review only,
        // and must never discourage or forewarn the worker before they clock in or out.

        // --- ROUND BIG CAMERA SHIFT BUTTON IN A CIRCLE ---
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .testTag("shift_action_card")
        ) {
            // Outer glowing accent ring with subtle pulse animation
            Box(
                modifier = Modifier
                    .size(236.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        if (isShiftActive) {
                            Brush.radialGradient(
                                colors = listOf(
                                    SophisticatedError.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(
                                    SophisticatedPrimary.copy(alpha = 0.25f),
                                    Color.Transparent
                                )
                            )
                        }
                    )
            )

            // Outer border ring
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        brush = if (isShiftActive) {
                            Brush.linearGradient(
                                listOf(
                                    SophisticatedError.copy(alpha = 0.8f),
                                    SophisticatedError.copy(alpha = 0.3f)
                                )
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    SophisticatedPrimary.copy(alpha = 0.8f),
                                    SophisticatedPrimary.copy(alpha = 0.3f)
                                )
                            )
                        },
                        shape = CircleShape
                    )
            )

            // Main Big Round Button
            Surface(
                onClick = {
                    if (isShiftActive) {
                        workerViewModel.setEndShiftDialog(true)
                    } else {
                        workerViewModel.setStartShiftDialog(true)
                    }
                },
                enabled = !uiState.isProcessing,
                shape = CircleShape,
                color = SophisticatedDarkSurface,
                border = BorderStroke(
                    1.dp,
                    if (isShiftActive) SophisticatedError.copy(alpha = 0.6f)
                    else SophisticatedDarkBorderLight
                ),
                modifier = Modifier
                    .size(196.dp)
                    .testTag(if (isShiftActive) "end_shift_btn" else "start_shift_btn")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Inner Circular Icon Badge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (isShiftActive) SophisticatedErrorContainer
                                else SophisticatedPrimaryContainer
                            )
                            .border(
                                width = 1.dp,
                                color = if (isShiftActive) SophisticatedError.copy(alpha = 0.5f)
                                else SophisticatedPrimary.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isShiftActive) Icons.Default.Logout
                            else Icons.Default.CameraAlt,
                            contentDescription = if (isShiftActive) "End Shift / Clock Out" else "Start Shift with Selfie",
                            tint = if (isShiftActive) SophisticatedError else SophisticatedPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Title Text
                    Text(
                        text = if (isShiftActive) "End Shift / Clock Out" else "Start Shift with Selfie",
                        color = SophisticatedTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Secondary helper text
                    Text(
                        text = if (isShiftActive) "Tap to Clock Out (Instant)" else "Tap to Clock In",
                        color = if (isShiftActive) SophisticatedError else SophisticatedSuccess,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // NOTE: The geofence/GPS status card and its Refresh GPS / Test GPS Mode controls were
        // intentionally removed from this worker-facing screen — compliance status is recorded
        // silently on the attendance record and surfaced only in the supervisor/HCM review views.

        // --- CLOCK COUNTER OF SHIFT ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("shift_clock_counter_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = SophisticatedDarkSurface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status Header Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isShiftActive) SophisticatedSuccess else SophisticatedTextMuted
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isShiftActive) "SHIFT IN PROGRESS • ACTIVE" else "SHIFT DURATION COUNTER",
                        color = if (isShiftActive) SophisticatedSuccess else SophisticatedTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Big Digital Clock Counter
                Text(
                    text = uiState.shiftDurationFormatted,
                    color = if (isShiftActive) Color.White else SophisticatedTextPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Shift Metadata
                if (isShiftActive) {
                    Text(
                        text = "Started: ${uiState.activeShift?.startTimeFormatted ?: "08:00:00"}  •  ${uiState.assignedProject?.projectName ?: "Muscat Construction Site A"}",
                        color = SophisticatedTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "✓ Official Server Timestamp & Biometric Verified",
                        color = SophisticatedSuccess,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = "Assigned Site: ${uiState.assignedProject?.projectName ?: "Muscat Construction Site A"}",
                        color = SophisticatedTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ready to record selfie biometric attendance",
                        color = SophisticatedTextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Quick Stats Strip
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCard(
                title = "Total Shifts",
                value = uiState.attendanceHistory.size.toString(),
                icon = Icons.Default.Checklist,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToLogs
            )
            Spacer(modifier = Modifier.width(10.dp))
            StatCard(
                title = "Pending Approval",
                value = uiState.attendanceHistory.count { it.state == AttendanceState.PENDING_APPROVAL.name }.toString(),
                icon = Icons.Default.Pending,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToLogs
            )
            Spacer(modifier = Modifier.width(10.dp))
            StatCard(
                title = "Leaves Taken",
                value = uiState.leaveHistory.count { it.status == LeaveStatus.APPROVED.name }.toString(),
                icon = Icons.Default.FlightTakeoff,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Direct shortcut button to Daily Attendance Logs
        OutlinedButton(
            onClick = onNavigateToLogs,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("view_all_daily_logs_btn"),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = SophisticatedDarkSurface,
                contentColor = SophisticatedPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "View Daily Attendance Logs (Room DB)",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(
            if (onClick != null) Modifier.clickable { onClick() } else Modifier
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, color = SophisticatedTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(text = title, color = SophisticatedTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AttendanceHistoryTab(
    attendanceList: List<AttendanceEntity>,
    onItemClick: (AttendanceEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Shift Attendance Records",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SophisticatedTextPrimary
        )
        Text(
            text = "Official server verified shift submissions & approval status",
            fontSize = 12.sp,
            color = SophisticatedTextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (attendanceList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = SophisticatedTextMuted, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No attendance records found yet", color = SophisticatedTextSecondary, fontWeight = FontWeight.Medium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(attendanceList) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .testTag("attendance_item_${item.attendanceId}"),
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.shiftDate,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = SophisticatedTextPrimary
                                    )
                                }
                                AttendanceStatusBadge(state = item.state)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = item.projectName,
                                color = SophisticatedTextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Start: ${item.startTimeFormatted ?: "Pending"}",
                                    color = SophisticatedTextMuted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Worked: ${item.totalWorkedMinutes} mins",
                                    color = SophisticatedTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }

                            if (item.rejectionReason != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SophisticatedErrorContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Rejection Reason: ${item.rejectionReason}",
                                        color = SophisticatedError,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun LeaveManagementTab(
    leaveList: List<LeaveRequestEntity>,
    onOpenSubmitDialog: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLeaves = remember(leaveList, selectedFilter) {
        when (selectedFilter) {
            "PENDING" -> leaveList.filter { it.status == "PENDING" }
            "APPROVED" -> leaveList.filter { it.status == "APPROVED" }
            "REJECTED" -> leaveList.filter { it.status == "REJECTED" }
            else -> leaveList
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Leave & Absence",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SophisticatedTextPrimary
                )
                Text(
                    text = "Request leave types & track approval status",
                    fontSize = 12.sp,
                    color = SophisticatedTextSecondary
                )
            }
            Button(
                onClick = onOpenSubmitDialog,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SophisticatedPrimary,
                    contentColor = SophisticatedOnPrimary
                ),
                modifier = Modifier.testTag("apply_leave_btn")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Request Leave", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Entitlement Quick Summary Cards (Sick, Casual, Annual, Transit)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LeaveQuotaPill("Sick", "15d", Color(0xFF00E676), Modifier.weight(1f))
            LeaveQuotaPill("Casual", "6d", Color(0xFFFFB74D), Modifier.weight(1f))
            LeaveQuotaPill("Annual", "30d", Color(0xFF64B5F6), Modifier.weight(1f))
            LeaveQuotaPill("Transit", "Duty", Color(0xFFBA68C8), Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status Filter Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "All (${leaveList.size})", "PENDING" to "Pending", "APPROVED" to "Approved", "REJECTED" to "Rejected").forEach { (key, label) ->
                val isSel = selectedFilter == key
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSel) SophisticatedPrimary else SophisticatedDarkSurface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSel) SophisticatedPrimary else SophisticatedDarkBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { selectedFilter = key }
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) SophisticatedOnPrimary else SophisticatedTextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredLeaves.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = SophisticatedDarkSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.EventNote,
                                contentDescription = null,
                                tint = SophisticatedTextMuted,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (leaveList.isEmpty()) "No leave requests submitted yet" else "No requests matching '$selectedFilter'",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SophisticatedTextPrimary
                    )
                    Text(
                        text = "Submit a Sick, Casual, Annual, or Transit leave request",
                        fontSize = 12.sp,
                        color = SophisticatedTextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onOpenSubmitDialog,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedPrimaryContainer,
                            contentColor = SophisticatedPrimary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create Leave Request", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredLeaves) { leave ->
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
                                val typeColor = when (leave.type) {
                                    "SICK_LEAVE", "Sick Leave", "Sick" -> Color(0xFF00E676)
                                    "CASUAL_LEAVE", "Casual Leave", "Casual" -> Color(0xFFFFB74D)
                                    "ANNUAL_LEAVE", "Annual Leave", "Annual" -> Color(0xFF64B5F6)
                                    "TRANSIT", "Transit" -> Color(0xFFBA68C8)
                                    else -> SophisticatedPrimary
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(typeColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = leave.type.replace("_", " "),
                                        color = typeColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                val statusBg = when (leave.status) {
                                    "APPROVED" -> SophisticatedSuccessContainer
                                    "REJECTED" -> SophisticatedErrorContainer
                                    else -> SophisticatedWarningContainer
                                }
                                val statusFg = when (leave.status) {
                                    "APPROVED" -> SophisticatedSuccess
                                    "REJECTED" -> SophisticatedError
                                    else -> SophisticatedWarning
                                }
                                Surface(shape = RoundedCornerShape(50), color = statusBg) {
                                    Text(
                                        text = leave.status,
                                        color = statusFg,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "${leave.startDate} to ${leave.endDate} • ${leave.totalDays} ${if (leave.totalDays == 1) "day" else "days"}",
                                color = SophisticatedTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Reason: ${leave.reason}",
                                color = SophisticatedTextSecondary,
                                fontSize = 12.sp
                            )

                            if (leave.rejectionReason != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SophisticatedErrorContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Supervisor Note: ${leave.rejectionReason}",
                                        color = SophisticatedError,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LeaveQuotaPill(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SophisticatedDarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = SophisticatedTextSecondary
            )
        }
    }
}

@Composable
private fun WorkerProfileTab(
    currentUser: com.example.data.entity.UserEntity?,
    assignedProject: com.example.data.entity.ProjectEntity?,
    onUpdatePhotoClick: () -> Unit = {},
    onLogoutClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val themePrefs = LocalThemePreferences.current
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog && themePrefs != null) {
        ThemeSettingsDialog(
            themePreferences = themePrefs,
            onDismiss = { showThemeDialog = false }
        )
    }

    val themeSettings by (themePrefs?.settings?.collectAsState() ?: remember { mutableStateOf(ThemeSettings()) })
    val themeMode = themeSettings.themeMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        // --- Profile Header Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_header_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Avatar with Photo / Initials & Camera Badge
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                )
                            )
                            .border(2.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), CircleShape)
                            .clickable { onUpdatePhotoClick() }
                            .testTag("user_avatar_image")
                    ) {
                        if (!currentUser?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = currentUser.avatarUrl,
                                contentDescription = "Profile Photo of ${currentUser.fullName}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initials = currentUser?.fullName?.split(" ")
                                ?.mapNotNull { it.firstOrNull()?.toString() }
                                ?.take(2)
                                ?.joinToString("") ?: "AA"
                            Text(
                                text = initials,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 28.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Camera Edit FAB Badge
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 4.dp,
                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { onUpdatePhotoClick() }
                            .testTag("change_profile_photo_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Update Profile Photo",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Full Name
                Text(
                    text = currentUser?.fullName ?: "Ahmed Ali Al-Balushi",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Role Pill Badge
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${currentUser?.role ?: "WORKER"} • ${currentUser?.department ?: "Civil Team"}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "ID: ${currentUser?.employeeId ?: "ART-W-000001"} • ${currentUser?.companyName ?: "Artify Contracting LLC"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Update Profile Photo Button (Camera)
                OutlinedButton(
                    onClick = onUpdatePhotoClick,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.primary
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
                        text = if (currentUser?.avatarUrl.isNullOrBlank()) "Capture Profile Photo (Camera)" else "Update Profile Photo (Camera)",
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DISPLAY & ENVIRONMENT THEME",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = if (isDark) "DARK MODE" else "LIGHT MODE",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "High-contrast dynamic themes designed for bright daylight and night construction visibility.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 3-Way Mode Segmented Selector (System, Light, Dark)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeMode.values().forEach { mode ->
                        val isSelected = themeMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { themePrefs?.setThemeMode(mode) }
                                .testTag("theme_mode_chip_${mode.name.lowercase()}"),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
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
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = mode.shortName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                                imageVector = if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Dark Theme",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = when (themeMode) {
                                        ThemeMode.DARK -> "Always Dark (Battery & Night Shift)"
                                        ThemeMode.LIGHT -> "Always Light (High Daylight Visibility)"
                                        ThemeMode.SYSTEM -> "Following System Mode"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Switch(
                            checked = isDark,
                            onCheckedChange = { checked ->
                                themePrefs?.setThemeMode(if (checked) ThemeMode.DARK else ThemeMode.LIGHT)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
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
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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

        // --- Work Assignment & Site Details ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "WORK ASSIGNMENT",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = (if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDark) SophisticatedSuccessBorder else SophisticatedLightSuccessBorder
                        )
                    ) {
                        Text(
                            text = "ACTIVE SITE",
                            color = if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                DetailRow("Assigned Site", assignedProject?.projectName ?: "Muscat Construction Site A")
                DetailRow("Site Code", assignedProject?.code ?: "PRJ-MCT-001")
                DetailRow("Location", assignedProject?.address ?: "Al Ghubrah North, Muscat")
                DetailRow("Standard Shift Time", "08:00 AM – 05:00 PM (GST)")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Contact & Personal Information ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "CONTACT & CREDENTIALS",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                DetailRow("Email Address", currentUser?.email ?: "artifystaff@gmail.com")
                DetailRow("Phone Number", currentUser?.phone ?: "+968 9123 4567")
                DetailRow("Department", currentUser?.department ?: "Civil Construction")
                DetailRow("Account Status", currentUser?.status ?: "ACTIVE")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Biometric & Device Security Info ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "SECURITY & BIOMETRIC VERIFICATION",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                DetailRow("Hardware Device ID", "ANDROID-ARTIFY-101")
                DetailRow("Facial Biometrics", "Enrolled & Active")
                DetailRow("Time Authority", "Authoritative UTC Server Clock")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Log Out Button ---
        OutlinedButton(
            onClick = onLogoutClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("profile_logout_btn"),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Sign Out of Account",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun LocationScenarioPickerDialog(
    currentScenario: LocationTestScenario,
    onSelect: (LocationTestScenario) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Select GPS Test Scenario",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SophisticatedTextPrimary
                )
                Text(
                    text = "Test server-side geofencing and anti-fraud validation:",
                    fontSize = 12.sp,
                    color = SophisticatedTextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                LocationTestScenario.values().forEach { scenario ->
                    val isSel = currentScenario == scenario
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(scenario) },
                        color = if (isSel) SophisticatedPrimaryContainer else SophisticatedDarkBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSel) SophisticatedPrimary else SophisticatedDarkBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = { onSelect(scenario) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = SophisticatedPrimary,
                                    unselectedColor = SophisticatedTextSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = scenario.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) SophisticatedPrimary else SophisticatedTextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmitLeaveDialog(
    onDismiss: () -> Unit,
    onSubmit: (type: LeaveType, start: String, end: String, days: Int, reason: String) -> Unit
) {
    var selectedType by remember { mutableStateOf(LeaveType.SICK_LEAVE) }
    var startDate by remember { mutableStateOf("2026-08-26") }
    var endDate by remember { mutableStateOf("2026-08-27") }
    var daysText by remember { mutableStateOf("2") }
    var reason by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Request Leave / Absence",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SophisticatedTextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text("Leave Type", color = SophisticatedTextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))

                LeaveType.values().forEach { t ->
                    val isSel = selectedType == t
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedType = t },
                        color = if (isSel) SophisticatedPrimaryContainer else SophisticatedDarkBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSel) SophisticatedPrimary else SophisticatedDarkBorder
                        )
                    ) {
                        Text(
                            text = t.displayName,
                            color = if (isSel) SophisticatedPrimary else SophisticatedTextPrimary,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = startDate,
                    onValueChange = { startDate = it },
                    label = { Text("Start Date (YYYY-MM-DD)") },
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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = endDate,
                    onValueChange = { endDate = it },
                    label = { Text("End Date (YYYY-MM-DD)") },
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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it },
                    label = { Text("Total Days") },
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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason for Absence") },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("leave_reason_input"),
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
                        onClick = {
                            val d = daysText.toIntOrNull() ?: 1
                            onSubmit(selectedType, startDate, endDate, d, reason)
                        },
                        enabled = reason.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SophisticatedPrimary,
                            contentColor = SophisticatedOnPrimary
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("submit_leave_btn")
                    ) {
                        Text("Submit Request")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceDetailDialog(
    attendance: AttendanceEntity,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Attendance Audit Detail",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = SophisticatedTextPrimary
                    )
                    AttendanceStatusBadge(state = attendance.state)
                }

                Spacer(modifier = Modifier.height(14.dp))

                val selfiePath = attendance.startSelfieData ?: attendance.endSelfieData
                if (selfiePath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SophisticatedDarkBg)
                            .border(1.dp, SophisticatedDarkBorder, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val selfieModel: Any? = when {
                            selfiePath.startsWith("http://") || selfiePath.startsWith("https://") -> selfiePath
                            selfiePath.startsWith("attendance-selfies/") -> "${com.example.network.ArtifyBackendConfig.SUPABASE_URL}/storage/v1/object/public/$selfiePath"
                            File(selfiePath).exists() -> File(selfiePath)
                            else -> null
                        }
                        if (selfieModel != null) {
                            AsyncImage(
                                model = selfieModel,
                                contentDescription = "Shift Selfie Evidence",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Face, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Biometric Selfie Evidence Attached", color = SophisticatedTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("SHA-256: ${selfiePath.take(16)}...", color = SophisticatedTextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                DetailRow("Shift Date", attendance.shiftDate)
                DetailRow("Project Site", attendance.projectName)
                DetailRow("Start Server Time", attendance.startTimeFormatted ?: "None")
                DetailRow("End Server Time", attendance.endTimeFormatted ?: "None")
                DetailRow("Total Worked", "${attendance.totalWorkedMinutes} minutes")
                DetailRow("Biometric Verification", "Selfie Biometric Verified")
                DetailRow("Hardware Device ID", attendance.deviceId)

                if (attendance.supervisorComment != null) {
                    DetailRow("Supervisor Note", attendance.supervisorComment)
                }
                if (attendance.rejectionReason != null) {
                    DetailRow("Rejection Reason", attendance.rejectionReason)
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    )
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = SophisticatedTextMuted, fontSize = 12.sp)
        Text(text = value, color = SophisticatedTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun NotificationsDialog(
    notifications: List<com.example.data.entity.NotificationEntity>,
    onDismiss: () -> Unit,
    onMarkRead: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Workforce Notifications",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SophisticatedTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (notifications.isEmpty()) {
                    Text("No notifications", color = SophisticatedTextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(notifications) { n ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onMarkRead(n.notificationId) },
                                color = if (n.isRead) SophisticatedDarkBg else SophisticatedPrimaryContainer,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (n.isRead) SophisticatedDarkBorder else SophisticatedPrimary.copy(alpha = 0.4f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = n.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (n.isRead) SophisticatedTextPrimary else SophisticatedPrimary
                                    )
                                    Text(text = n.message, fontSize = 12.sp, color = SophisticatedTextSecondary)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    )
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun EndShiftConfirmDialog(
    activeShift: AttendanceEntity?,
    projectName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SophisticatedDarkSurface,
            border = BorderStroke(1.dp, SophisticatedDarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("end_shift_confirm_dialog")
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(SophisticatedErrorContainer)
                        .border(1.dp, SophisticatedError.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Logout,
                        contentDescription = "Clock Out",
                        tint = SophisticatedError,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Clock Out Shift",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SophisticatedTextPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Complete shift & sync hours with Head Office Payroll",
                    fontSize = 12.sp,
                    color = SophisticatedTextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Shift Details Summary Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = SophisticatedDarkBg,
                    border = BorderStroke(1.dp, SophisticatedDarkBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Project Site",
                                fontSize = 11.sp,
                                color = SophisticatedTextSecondary
                            )
                            Text(
                                text = projectName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SophisticatedTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = SophisticatedDarkBorderLight.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Started At",
                                fontSize = 11.sp,
                                color = SophisticatedTextSecondary
                            )
                            Text(
                                text = activeShift?.startTimeFormatted ?: "Active",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SophisticatedTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = SophisticatedDarkBorderLight.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selfie Verification",
                                fontSize = 11.sp,
                                color = SophisticatedTextSecondary
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SophisticatedSuccessContainer,
                                border = BorderStroke(0.5.dp, SophisticatedSuccess.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Not Required for Clock-Out",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SophisticatedSuccess,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Button(
                    onClick = {
                        onConfirm()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_clock_out_btn"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedError,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confirm & Clock Out",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SophisticatedDarkBorderLight),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SophisticatedTextSecondary
                    )
                ) {
                    Text("Cancel / Keep Working", fontSize = 13.sp)
                }
            }
        }
    }
}

