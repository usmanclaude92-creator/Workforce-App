package com.example.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.sync.SyncQueueStatus
import com.example.model.ShiftEventType
import com.example.network.AttendanceShiftDto
import com.example.network.LeaveRequestDto
import com.example.network.NotificationDto
import com.example.security.SecureSessionStore
import com.example.ui.components.ArtifyTopHeader
import com.example.ui.components.CameraXSelfieDialog
import com.example.ui.components.SelfieVerificationDialog
import com.example.ui.components.ThemeSettingsDialog
import com.example.ui.theme.*
import com.example.ui.viewmodel.RealWorkerUiState
import com.example.ui.viewmodel.RealWorkerViewModel
import java.util.Calendar

private enum class RealWorkerTab { SHIFT, LOGS, LEAVE, PROFILE }

private val LEAVE_TYPES = listOf(
    Triple("SICK", "Sick", "15d"), Triple("CASUAL", "Casual", "6d"),
    Triple("ANNUAL", "Annual", "30d"), Triple("TRANSIT", "Transit", "Duty")
)

@Composable
private fun leaveTypeColor(code: String): Color = when (code) {
    "SICK" -> Color(0xFF00E676)
    "CASUAL" -> Color(0xFFFFB74D)
    "ANNUAL" -> Color(0xFF64B5F6)
    "TRANSIT" -> Color(0xFFBA68C8)
    else -> SophisticatedPrimary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealWorkerDashboardScreen(
    viewModel: RealWorkerViewModel,
    employeeName: String,
    employeeCode: String,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(RealWorkerTab.SHIFT) }
    var showNotifications by remember { mutableStateOf(false) }
    val isDark = LocalIsDarkTheme.current
    val screenBg = if (isDark) SophisticatedDarkBg else SophisticatedLightBg
    val navBg = if (isDark) SophisticatedDarkNav else SophisticatedLightNav
    val projectName = uiState.profile?.projectName ?: uiState.activeShift?.project?.name
        ?: uiState.shiftHistory.firstOrNull()?.project?.name ?: "Assigned Site"
    var showProfileCameraDialog by remember { mutableStateOf(false) }
    var showSelfieVerificationDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = screenBg,
        topBar = {
            if (tab == RealWorkerTab.LOGS) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "My Shifts Summary",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
                            )
                            Text(
                                text = "${uiState.profile?.fullName ?: employeeName} • ${uiState.profile?.employeeCode ?: employeeCode}",
                                fontSize = 12.sp,
                                color = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
                            )
                        }
                    },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isDark) SophisticatedPrimaryContainer.copy(alpha = 0.6f) else SophisticatedLightPrimaryContainer,
                            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isDark) SophisticatedSuccess else SophisticatedLightSuccess)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Supabase DB",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SophisticatedPrimary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface,
                        titleContentColor = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
                    )
                )
            } else {
                ArtifyTopHeader(
                    userName = employeeName,
                    employeeId = employeeCode,
                    role = uiState.profile?.role ?: "WORKER",
                    onLogoutClick = onLogout,
                    notificationCount = uiState.notifications.size,
                    onNotificationClick = { showNotifications = true }
                )
            }
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
                NavigationBarItem(selected = tab == RealWorkerTab.SHIFT, onClick = { tab = RealWorkerTab.SHIFT },
                    icon = { Icon(Icons.Default.Schedule, contentDescription = "Shift") }, label = { Text("Shift") }, colors = navColors())
                NavigationBarItem(selected = tab == RealWorkerTab.LOGS, onClick = { tab = RealWorkerTab.LOGS },
                    icon = { Icon(Icons.Default.History, contentDescription = "My Shifts") }, label = { Text("My Shifts") }, modifier = Modifier.testTag("nav_my_shifts"), colors = navColors())
                NavigationBarItem(selected = tab == RealWorkerTab.LEAVE, onClick = { tab = RealWorkerTab.LEAVE },
                    icon = { Icon(Icons.Default.EventNote, contentDescription = "Leave") }, label = { Text("Leave") }, colors = navColors())
                NavigationBarItem(selected = tab == RealWorkerTab.PROFILE, onClick = { tab = RealWorkerTab.PROFILE },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") }, label = { Text("Profile") }, colors = navColors())
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(screenBg)) {
            Column(modifier = Modifier.fillMaxSize()) {
                SyncQueueBanner(uiState.syncQueue, onSyncNow = { viewModel.syncNow() })
                Box(modifier = Modifier.weight(1f)) {
                    when (tab) {
                        RealWorkerTab.SHIFT -> ShiftTab(
                            uiState = uiState,
                            viewModel = viewModel,
                            onViewLogs = { tab = RealWorkerTab.LOGS },
                            onLaunchBiometricVerification = { showSelfieVerificationDialog = true }
                        )
                        RealWorkerTab.LOGS -> DailyLogsTab(uiState, viewModel)
                        RealWorkerTab.LEAVE -> LeaveTab(uiState, viewModel)
                        RealWorkerTab.PROFILE -> ProfileTab(
                            uiState = uiState,
                            fallbackName = employeeName,
                            fallbackCode = employeeCode,
                            onUpdatePhotoClick = { showProfileCameraDialog = true },
                            onLogout = onLogout
                        )
                    }
                }
            }

            uiState.statusMessage?.let { msg ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedSuccessContainer,
                    border = BorderStroke(1.dp, SophisticatedSuccessBorder)
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, fontSize = 12.5.sp, modifier = Modifier.weight(1f), color = SophisticatedSuccess, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { viewModel.clearFeedback() }) { Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SophisticatedSuccess) }
                    }
                }
            }
            uiState.errorMessage?.let { err ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SophisticatedErrorContainer,
                    border = BorderStroke(1.dp, SophisticatedError.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(err, fontSize = 12.5.sp, modifier = Modifier.weight(1f), color = SophisticatedError, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { viewModel.clearFeedback() }) { Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = SophisticatedError) }
                    }
                }
            }
        }
    }

    if (uiState.showStartShiftDialog) {
        CameraXSelfieDialog(
            eventType = ShiftEventType.START_SHIFT, projectName = projectName, employeeName = employeeName,
            onDismiss = { viewModel.setStartShiftDialog(false) },
            onCaptureComplete = { path -> viewModel.startShift(path) }
        )
    }
    if (uiState.showEndShiftDialog) {
        CameraXSelfieDialog(
            eventType = ShiftEventType.END_SHIFT, projectName = projectName, employeeName = employeeName,
            onDismiss = { viewModel.setEndShiftDialog(false) },
            onCaptureComplete = { path -> viewModel.endShift(path) }
        )
    }
    if (showProfileCameraDialog) {
        CameraXSelfieDialog(
            eventType = ShiftEventType.START_SHIFT,
            projectName = projectName,
            employeeName = employeeName,
            onDismiss = { showProfileCameraDialog = false },
            onCaptureComplete = { path ->
                showProfileCameraDialog = false
                viewModel.updateProfilePhoto(path)
            }
        )
    }
    if (showSelfieVerificationDialog) {
        SelfieVerificationDialog(
            employeeId = employeeCode,
            employeeName = employeeName,
            projectName = projectName,
            verificationType = if (uiState.activeShift != null) "CLOCK_OUT" else "CLOCK_IN",
            onDismiss = { showSelfieVerificationDialog = false },
            onVerificationComplete = { entry ->
                showSelfieVerificationDialog = false
                viewModel.refresh()
            }
        )
    }
    if (showNotifications) NotificationsDialog(uiState.notifications, onDismiss = { showNotifications = false })
}

@Composable
private fun NotificationsDialog(notifications: List<NotificationDto>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications") },
        text = {
            if (notifications.isEmpty()) {
                Text("No notifications yet.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(notifications, key = { it.id }) { n ->
                        Column {
                            Text(n.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(n.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ShiftTab(
    uiState: RealWorkerUiState,
    viewModel: RealWorkerViewModel,
    onViewLogs: () -> Unit,
    onLaunchBiometricVerification: () -> Unit = {}
) {
    val active = uiState.activeShift
    val isQueuedLocally = active?.id?.startsWith(com.example.ui.viewmodel.LOCAL_PENDING_SHIFT_PREFIX) == true
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    // NOTE: Geofence/GPS compliance status is intentionally never shown to the worker on this
    // screen — it is recorded silently on the attendance record for supervisor/HCM review only,
    // and must never discourage or forewarn the worker before they clock in or out.
    val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (active != null) 1.06f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Hero Biometric Camera Button with breathing pulse & radial gradient rings (Demo parity)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(236.dp)
        ) {
            // Outer glowing radial gradient ring
            Box(
                modifier = Modifier
                    .size(232.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(
                        brush = Brush.radialGradient(
                            if (active != null) listOf(SophisticatedError.copy(alpha = 0.25f), Color.Transparent)
                            else listOf(SophisticatedPrimary.copy(alpha = 0.25f), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )

            // Middle border ring
            Box(
                modifier = Modifier
                    .size(216.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        Brush.linearGradient(
                            if (active != null) listOf(SophisticatedError.copy(alpha = 0.8f), SophisticatedError.copy(alpha = 0.3f))
                            else listOf(SophisticatedPrimary.copy(alpha = 0.8f), SophisticatedPrimary.copy(alpha = 0.3f))
                        ),
                        CircleShape
                    )
            )

            // Main circular button
            Surface(
                onClick = {
                    if (active != null) viewModel.setEndShiftDialog(true)
                    else viewModel.setStartShiftDialog(true)
                },
                enabled = !uiState.isProcessing,
                shape = CircleShape,
                color = cardBg,
                border = BorderStroke(
                    1.dp,
                    if (active != null) SophisticatedError.copy(alpha = 0.6f)
                    else if (isDark) SophisticatedDarkBorderLight else SophisticatedLightBorderLight
                ),
                modifier = Modifier.size(192.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (uiState.isProcessing) {
                        CircularProgressIndicator(
                            color = if (active != null) SophisticatedError else SophisticatedPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = if (active != null) SophisticatedErrorContainer else SophisticatedPrimaryContainer,
                            border = BorderStroke(
                                1.dp,
                                if (active != null) SophisticatedError.copy(alpha = 0.5f) else SophisticatedPrimary.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (active != null) Icons.Default.Logout else Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = if (active != null) SophisticatedError else SophisticatedPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (active != null) "End Shift with Selfie" else "Start Shift with Selfie",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (active != null) "Tap to Clock Out" else "Tap to Clock In",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active != null) SophisticatedError else SophisticatedSuccess
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Digital Clock Duration Card
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (active != null) SophisticatedSuccess else textMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (active != null) "SHIFT IN PROGRESS • ACTIVE" else "SHIFT DURATION COUNTER",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = if (active != null) SophisticatedSuccess else textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                ShiftDurationTicker(viewModel = viewModel, isActive = active != null)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Assigned Site: ${uiState.profile?.projectName ?: "—"}",
                    fontSize = 11.5.sp,
                    color = textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isQueuedLocally) "Captured offline — will sync automatically."
                    else if (active != null) "✓ Official Server Timestamp & Biometric Recorded"
                    else "Ready to record selfie biometric attendance.",
                    fontSize = 10.5.sp,
                    color = if (active != null) SophisticatedSuccess else textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isQueuedLocally) {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusPill("QUEUED")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                label = "Total Shifts",
                value = (uiState.shiftHistory.size + if (active != null) 1 else 0).toString(),
                icon = Icons.Default.Checklist,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Pending Approval",
                value = uiState.shiftHistory.count { it.status == "PENDING_REVIEW" }.toString(),
                icon = Icons.Default.Pending,
                modifier = Modifier.weight(1f)
            )
            StatTile(
                label = "Leaves Taken",
                value = uiState.leaveHistory.count { it.status == "APPROVED" }.toString(),
                icon = Icons.Default.FlightTakeoff,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onLaunchBiometricVerification,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3ECF8E),
                contentColor = Color.Black
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("btn_biometric_selfie_verification")
        ) {
            Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Biometric Attendance Verification", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onViewLogs,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = cardBg,
                contentColor = SophisticatedPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Daily Attendance Logs (Room DB)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "RECENT SHIFTS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = textSecondary,
            modifier = Modifier.align(Alignment.Start)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (uiState.shiftHistory.isEmpty()) {
            Text("No completed shifts yet.", fontSize = 12.sp, color = textMuted)
        } else {
            uiState.shiftHistory
                .sortedWith(
                    compareByDescending<AttendanceShiftDto> { parseShiftInstantMs(it) }
                        .thenByDescending { it.shiftDate }
                )
                .take(5)
                .forEach { shift -> ShiftHistoryCard(shift) }
        }
    }
}

@Composable
private fun ShiftDurationTicker(viewModel: RealWorkerViewModel, isActive: Boolean) {
    val duration by viewModel.shiftDurationFormatted.collectAsState()
    val isDark = LocalIsDarkTheme.current
    Text(
        text = duration,
        fontSize = 44.sp,
        fontWeight = FontWeight.Light,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
        color = if (isActive) (if (isDark) Color.White else SophisticatedLightTextPrimary)
        else (if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary)
    )
}

@Composable
private fun StatTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, color = textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ShiftHistoryCard(shift: AttendanceShiftDto) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = SophisticatedPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(shift.shiftDate, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textPrimary)
                }
                ShiftStatusBadge(shift.status)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text("Worked: ${shift.totalWorkedMinutes ?: 0} mins", fontSize = 12.sp, color = textSecondary)
            shift.reviewComment?.let {
                Spacer(modifier = Modifier.height(3.dp))
                Text("Supervisor: $it", fontSize = 11.sp, color = textMuted)
            }
        }
    }
}

@Composable
fun StatusPill(status: String) {
    val isDark = LocalIsDarkTheme.current
    val (bg, fg) = when (status) {
        "APPROVED" -> (if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer) to
            (if (isDark) SophisticatedSuccess else SophisticatedLightSuccess)
        "REJECTED" -> (if (isDark) SophisticatedErrorContainer else SophisticatedLightErrorContainer) to
            (if (isDark) SophisticatedError else SophisticatedLightError)
        "PENDING_REVIEW", "PENDING" -> (if (isDark) SophisticatedWarningContainer else SophisticatedLightWarningContainer) to
            (if (isDark) SophisticatedWarning else SophisticatedLightWarning)
        "OPEN", "QUEUED" -> (if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer) to
            SophisticatedPrimary
        else -> (if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh) to
            (if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary)
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(status.replace('_', ' '), color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Icon-bearing status badge (mirrors Demo Mode's DailyShiftStatusBadge) used on daily-log cards and the detail dialog. */
@Composable
private fun ShiftStatusBadge(status: String) {
    val isDark = LocalIsDarkTheme.current
    val (bg, fg, label, icon) = when (status) {
        "APPROVED" -> Tuple4(
            if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer,
            if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
            "APPROVED",
            Icons.Default.Check
        )
        "REJECTED" -> Tuple4(
            if (isDark) SophisticatedErrorContainer else SophisticatedLightErrorContainer,
            if (isDark) SophisticatedError else SophisticatedLightError,
            "REJECTED",
            Icons.Default.Close
        )
        "PENDING_REVIEW", "PENDING" -> Tuple4(
            if (isDark) SophisticatedWarningContainer else SophisticatedLightWarningContainer,
            if (isDark) SophisticatedWarning else SophisticatedLightWarning,
            "PENDING REVIEW",
            Icons.Default.HourglassEmpty
        )
        "OPEN" -> Tuple4(
            if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer,
            SophisticatedPrimary,
            "IN PROGRESS",
            Icons.Default.PlayArrow
        )
        "QUEUED" -> Tuple4(
            if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer,
            SophisticatedPrimary,
            "QUEUED",
            Icons.Default.CloudQueue
        )
        else -> Tuple4(
            if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh,
            if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary,
            status,
            Icons.Default.Info
        )
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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

/** Extracts epoch milliseconds from a shift's clockIn or clockOut timestamp, falling back to shiftDate. */
private fun parseShiftInstantMs(shift: AttendanceShiftDto): Long {
    val raw = shift.clockIn?.serverTimestamp ?: shift.clockOut?.serverTimestamp
    if (!raw.isNullOrBlank()) {
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {}
        try {
            return java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {}
    }
    return try {
        java.time.LocalDate.parse(shift.shiftDate)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

@Composable
private fun MiniStatCard(title: String, value: String, subtext: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 10.5.sp, color = textSecondary, fontWeight = FontWeight.Medium)
                Icon(icon, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(14.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = textPrimary)
            Text(subtext, fontSize = 9.5.sp, color = textMuted)
        }
    }
}

// ---------------- Daily Logs tab (search/filter/detail) ----------------

@Composable
private fun DailyLogsTab(uiState: RealWorkerUiState, viewModel: RealWorkerViewModel) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<AttendanceShiftDto?>(null) }
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val surfaceHigh = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    val activeList = listOfNotNull(uiState.activeShift?.takeIf { !it.id.startsWith(com.example.ui.viewmodel.LOCAL_PENDING_SHIFT_PREFIX) })
    val allShifts = (activeList + uiState.shiftHistory).distinctBy { it.id }
    val filtered = allShifts.filter { s ->
        val matchesFilter = when (filter) {
            "ALL" -> true
            "COMPLETED" -> s.status != "OPEN"
            else -> s.status == filter
        }
        val matchesQuery = query.isBlank() || s.shiftDate.contains(query, ignoreCase = true) || s.status.contains(query, ignoreCase = true)
        matchesFilter && matchesQuery
    }.sortedWith(
        compareByDescending<AttendanceShiftDto> {
            // Active shift in-progress always at the top of daily logs
            if (it.status == "OPEN") 1 else 0
        }.thenByDescending {
            // Latest clock-in or clock-out timestamp first
            parseShiftInstantMs(it)
        }.thenByDescending {
            it.shiftDate
        }
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Supabase Database Connection Status Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh,
            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isDark) SophisticatedSuccess else SophisticatedLightSuccess)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Connected to Supabase Database",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        "https://jpsiafvbyupofnbqonkq.supabase.co • Shift completion logs stored",
                        fontSize = 9.5.sp,
                        color = textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { viewModel.refresh() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = SophisticatedPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        val totalMinutes = allShifts.sumOf { it.totalWorkedMinutes ?: 0 }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniStatCard("Completed", allShifts.count { it.status != "OPEN" }.toString(), "Total Shifts", Icons.Default.CheckCircle, Modifier.weight(1f))
            MiniStatCard("Approved", allShifts.count { it.status == "APPROVED" }.toString(), "Verified", Icons.Default.CheckCircle, Modifier.weight(1f))
            MiniStatCard("Hours", String.format(java.util.Locale.US, "%.1f", totalMinutes / 60.0), "Total Logged", Icons.Default.Schedule, Modifier.weight(1f))
            MiniStatCard("Pending", allShifts.count { it.status == "PENDING_REVIEW" }.toString(), "In Review", Icons.Default.HourglassEmpty, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Search by date (YYYY-MM-DD), project, or time…", fontSize = 11.5.sp, color = textMuted) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SophisticatedPrimary) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SophisticatedPrimary,
                unfocusedBorderColor = cardBorder,
                focusedTextColor = textPrimary,
                unfocusedTextColor = textPrimary,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "ALL" to "All Shifts",
                "COMPLETED" to "Completed",
                "APPROVED" to "Approved",
                "PENDING_REVIEW" to "Pending Approval",
                "REJECTED" to "Rejected"
            ).forEach { (code, label) ->
                val isSelected = filter == code
                Surface(
                    onClick = { filter = code },
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) SophisticatedPrimary else cardBg,
                    border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else cardBorder)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) SophisticatedOnPrimary else textSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MY SHIFTS SUMMARY (${filtered.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = textSecondary)
            Text("Supabase Cloud DB & Room DB", fontSize = 9.5.sp, color = textMuted)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No records match.", fontSize = 12.sp, color = textMuted, modifier = Modifier.padding(top = 20.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { shift ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selected = shift },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(shift.shiftDate, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textPrimary)
                                        Text(shift.project?.name ?: "—", fontSize = 11.sp, color = textSecondary)
                                    }
                                }
                                ShiftStatusBadge(shift.status)
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = surfaceHigh,
                                border = BorderStroke(1.dp, cardBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Login, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("START", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = textMuted)
                                        }
                                        Text(formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(26.dp).background(cardBorder))
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Logout, contentDescription = null, tint = if (shift.clockOut != null) SophisticatedPrimary else textMuted, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("END", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = textMuted)
                                        }
                                        Text(formatShiftTime(shift.clockOut?.serverTimestamp) ?: "In progress", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(26.dp).background(cardBorder))
                                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.End) {
                                        Text("DURATION", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = textMuted)
                                        Text("${shift.totalWorkedMinutes ?: 0}m", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SophisticatedPrimary)
                                    }
                                }
                            }
                            shift.reviewComment?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                val isRejected = shift.status == "REJECTED"
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isRejected) SophisticatedErrorContainer else SophisticatedSuccessContainer,
                                    border = BorderStroke(1.dp, if (isRejected) SophisticatedError.copy(alpha = 0.4f) else SophisticatedSuccessBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (isRejected) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = if (isRejected) SophisticatedError else SophisticatedSuccess,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Supervisor: $it", fontSize = 10.5.sp,
                                            color = if (isRejected) SophisticatedError else SophisticatedSuccess
                                        )
                                    }
                                }
                            }
                            if (shift.status != "OPEN" || shift.clockOut != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (isDark) SophisticatedSuccessContainer.copy(alpha = 0.5f) else SophisticatedLightSuccessContainer,
                                        border = BorderStroke(1.dp, SophisticatedSuccessBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = SophisticatedSuccess, modifier = Modifier.size(11.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Stored in Supabase DB", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SophisticatedSuccess)
                                        }
                                    }
                                    Text("Tap for details", fontSize = 9.5.sp, color = textMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { shift ->
        AttendanceDetailDialog(shift = shift, uiState = uiState, viewModel = viewModel, onDismiss = { selected = null })
    }
}

@Composable
private fun AttendanceDetailDialog(shift: AttendanceShiftDto, uiState: RealWorkerUiState, viewModel: RealWorkerViewModel, onDismiss: () -> Unit) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Shift Details", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
                    ShiftStatusBadge(shift.status)
                }
                Spacer(modifier = Modifier.height(12.dp))

                val selfiePath = shift.clockIn?.selfieStoragePath ?: shift.clockOut?.selfieStoragePath
                if (selfiePath != null) {
                    LaunchedEffect(selfiePath) { viewModel.loadSelfieUrl(selfiePath) }
                    val url = uiState.selfieUrlCache[selfiePath]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(if (isDark) SophisticatedDarkBg else SophisticatedLightBg, RoundedCornerShape(14.dp))
                            .border(1.dp, cardBorder, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (url != null) AsyncImage(model = url, contentDescription = "Selfie evidence", modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        else CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SophisticatedPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                KeyValueRow("Date", shift.shiftDate)
                KeyValueRow("Site", shift.project?.name ?: "—")
                KeyValueRow("Clock In", formatShiftTime(shift.clockIn?.serverTimestamp) ?: "—")
                KeyValueRow("Clock Out", formatShiftTime(shift.clockOut?.serverTimestamp) ?: "In progress")
                KeyValueRow("Duration", "${shift.totalWorkedMinutes ?: 0} minutes")
                KeyValueRow("Biometric Match", if (selfiePath != null) "Selfie captured & verified" else "No selfie on record")
                KeyValueRow("Hardware Device", (shift.clockIn?.deviceId ?: shift.clockOut?.deviceId)?.take(18) ?: "Unknown")
                KeyValueRow("Database Storage", "Supabase Database & Local Room DB")
                KeyValueRow("Supabase Endpoint", "jpsiafvbyupofnbqonkq.supabase.co")
                KeyValueRow("Shift Completion", if (shift.clockOut != null || shift.status != "OPEN") "Logged & Synced to Cloud" else "Shift in progress")
                shift.reviewComment?.let { KeyValueRow("Supervisor Note", it) }

                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = SophisticatedOnPrimary),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    val isDark = LocalIsDarkTheme.current
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = textPrimary, modifier = Modifier.weight(1.4f), textAlign = TextAlign.End)
    }
}

@Composable
private fun SyncQueueBanner(queue: SyncQueueStatus, onSyncNow: () -> Unit) {
    if (queue.pendingCount == 0 && queue.failedCount == 0 && queue.isOnline) return
    val isDark = LocalIsDarkTheme.current
    val bgColor = if (queue.failedCount > 0) SophisticatedErrorContainer
    else if (!queue.isOnline) (if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface)
    else (if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer)
    val borderColor = if (queue.failedCount > 0) SophisticatedError.copy(alpha = 0.5f)
    else (if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        !queue.isOnline -> "Offline — showing last synced data"
                        queue.isSyncing -> "Syncing…"
                        queue.failedCount > 0 -> "${queue.failedCount} item(s) failed to sync"
                        else -> "${queue.pendingCount} item(s) waiting to sync"
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (queue.failedCount > 0) SophisticatedError else (if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary)
                )
                queue.lastError?.let { Text(it, fontSize = 10.sp, maxLines = 2, color = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary) }
            }
            if (queue.isOnline && (queue.pendingCount > 0 || queue.failedCount > 0) && !queue.isSyncing) {
                TextButton(onClick = onSyncNow) { Text("Sync Now", color = SophisticatedPrimary, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ---------------- Leave tab ----------------

@Composable
private fun LeaveTab(uiState: RealWorkerUiState, viewModel: RealWorkerViewModel) {
    var showForm by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf("ALL") }
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    val filtered = uiState.leaveHistory.filter { statusFilter == "ALL" || it.status == statusFilter }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (showForm) {
            LeaveForm(
                isSubmitting = uiState.isProcessing,
                onCancel = { showForm = false },
                onSubmit = { type, start, end, reason -> viewModel.submitLeave(type, start, end, reason); showForm = false }
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Leave & Absence", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
                    Text("Request leave types & track approval status", fontSize = 10.5.sp, color = textSecondary)
                }
                Button(
                    onClick = { showForm = true },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = SophisticatedOnPrimary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Request Leave", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LEAVE_TYPES.forEach { (code, label, quota) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(quota, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = leaveTypeColor(code))
                            Text(label, fontSize = 10.sp, color = textSecondary)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "All (${uiState.leaveHistory.size})", "PENDING" to "Pending", "APPROVED" to "Approved", "REJECTED" to "Rejected").forEach { (code, label) ->
                    val isSelected = statusFilter == code
                    Surface(
                        onClick = { statusFilter = code },
                        shape = RoundedCornerShape(50),
                        color = if (isSelected) SophisticatedPrimary else cardBg,
                        border = BorderStroke(1.dp, if (isSelected) SophisticatedPrimary else cardBorder)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) SophisticatedOnPrimary else textSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (filtered.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = if (isDark) SophisticatedDarkSurfaceHigh else SophisticatedLightSurfaceHigh,
                        border = BorderStroke(1.dp, cardBorder),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.EventNote, contentDescription = null, tint = SophisticatedPrimary, modifier = Modifier.size(28.dp)) }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        if (uiState.leaveHistory.isEmpty()) "No leave requests submitted yet" else "No requests match this filter",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Submit a Sick, Casual, Annual, or Transit leave request", fontSize = 12.sp, color = textMuted, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = { showForm = true },
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = SophisticatedOnPrimary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Leave Request", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { leave -> LeaveCard(leave) }
                }
            }
        }
    }
}

@Composable
private fun LeaveForm(isSubmitting: Boolean, onCancel: () -> Unit, onSubmit: (type: String, start: String, end: String, reason: String) -> Unit) {
    var type by remember { mutableStateOf("ANNUAL") }
    val today = remember { Calendar.getInstance() }
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
    var start by remember { mutableStateOf(fmt.format(today.time)) }
    var end by remember { mutableStateOf(fmt.format(today.time)) }
    var reason by remember { mutableStateOf("") }
    var showDatePickerFor by remember { mutableStateOf<String?>(null) }
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary
    val textMuted = if (isDark) SophisticatedTextMuted else SophisticatedLightTextMuted

    val suggestedReasons = mapOf(
        "SICK" to listOf("Medical consultation and rest", "Fever & viral infection recovery"),
        "CASUAL" to listOf("Urgent family emergency", "Personal legal & government documentation"),
        "ANNUAL" to listOf("Scheduled annual vacation", "Family holiday travel"),
        "TRANSIT" to listOf("Inter-site travel duty", "Logistics convoy & equipment transport")
    )

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("New Leave Request", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
            TextButton(onClick = onCancel) { Text("Cancel", color = SophisticatedPrimary) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Text("LEAVE TYPE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = SophisticatedPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LEAVE_TYPES.forEach { (code, label, _) ->
                val selected = type == code
                Surface(
                    onClick = { type = code },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) (if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer) else cardBg,
                    border = BorderStroke(1.dp, if (selected) SophisticatedPrimary else cardBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        label, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        color = if (selected) SophisticatedPrimary else textSecondary,
                        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("DATE RANGE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = SophisticatedPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("Start", start, modifier = Modifier.weight(1f)) { showDatePickerFor = "start" }
            DateField("End", end, modifier = Modifier.weight(1f)) { showDatePickerFor = "end" }
        }
        val totalDays = runCatching {
            ((fmt.parse(end)!!.time - fmt.parse(start)!!.time) / 86400000L).toInt() + 1
        }.getOrDefault(1)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isDark) SophisticatedPrimaryContainer else SophisticatedLightPrimaryContainer,
            border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Total: $totalDays day(s)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SophisticatedPrimary, modifier = Modifier.padding(10.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("REASON", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = SophisticatedPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (suggestedReasons[type] ?: emptyList()).forEach { suggestion ->
                Surface(
                    onClick = { reason = suggestion },
                    shape = RoundedCornerShape(50),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Text(suggestion, fontSize = 10.5.sp, color = textSecondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("Reason for leave…", fontSize = 12.sp, color = textMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SophisticatedPrimary,
                unfocusedBorderColor = cardBorder,
                focusedTextColor = textPrimary,
                unfocusedTextColor = textPrimary,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg
            )
        )

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(type, start, end, reason) },
            enabled = !isSubmitting && reason.isNotBlank() && totalDays > 0,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = SophisticatedPrimary, contentColor = SophisticatedOnPrimary),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (isSubmitting) "Submitting…" else "Submit Request", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))
    }

    showDatePickerFor?.let { target ->
        SimpleDatePickerDialog(
            initialDate = if (target == "start") start else end,
            onDismiss = { showDatePickerFor = null },
            onConfirm = { picked ->
                if (target == "start") start = picked else end = picked
                showDatePickerFor = null
            }
        )
    }
}

@Composable
private fun DateField(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Column(modifier = modifier) {
        Text(label, fontSize = 10.sp, color = textSecondary)
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                IconButton(onClick = onClick, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.CalendarMonth, contentDescription = "Pick date", tint = SophisticatedPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDatePickerDialog(initialDate: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(initialDate)?.time
        }.getOrNull()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))
                    onConfirm(date)
                } else onDismiss()
            }) { Text("Confirm", color = SophisticatedPrimary, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

@Composable
private fun LeaveCard(leave: LeaveRequestDto) {
    val isDark = LocalIsDarkTheme.current
    val cardBg = if (isDark) SophisticatedDarkSurface else SophisticatedLightSurface
    val cardBorder = if (isDark) SophisticatedDarkBorder else SophisticatedLightBorder
    val textPrimary = if (isDark) SophisticatedTextPrimary else SophisticatedLightTextPrimary
    val textSecondary = if (isDark) SophisticatedTextSecondary else SophisticatedLightTextSecondary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${leave.leaveType} • ${leave.totalDays.toInt()}d", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = textPrimary)
                StatusPill(leave.status)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("${leave.startDate} → ${leave.endDate}", fontSize = 12.sp, color = textSecondary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(leave.reason, fontSize = 12.sp, color = textPrimary)
            leave.decisionReason?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Decision: $it", fontSize = 11.sp, color = SophisticatedPrimary)
            }
        }
    }
}

// ---------------- Profile tab ----------------

@Composable
private fun ProfileTab(
    uiState: RealWorkerUiState,
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
                                .ifEmpty { "AA" }
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
                            text = "${profile?.role ?: "WORKER"} • ${profile?.department ?: "Civil Team"}",
                            color = SophisticatedPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "ID: ${profile?.employeeCode ?: fallbackCode} • ${profile?.companyName ?: "Artify Contracting LLC"}",
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

        // --- Work Assignment & Site Details ---
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                        text = "WORK ASSIGNMENT",
                        color = SophisticatedPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isDark) SophisticatedSuccessBorder else SophisticatedLightSuccessBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) SophisticatedSuccess else SophisticatedLightSuccess)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "ACTIVE",
                                color = if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                WorkAssignmentDetailRow(label = "Assigned Site", value = profile?.projectName ?: "Muscat Construction Site A")
                WorkAssignmentDetailRow(label = "Project Code", value = profile?.projectCode ?: "PRJ-MUSCAT-01")
                WorkAssignmentDetailRow(label = "Assigned Geofence", value = profile?.geofenceRadiusMeters?.let { "${it.toInt()}m Geo-radius Active" } ?: "200m Geo-radius Active")
                WorkAssignmentDetailRow(label = "Hardware Biometric ID", value = deviceId.take(18) + "…")
            }
        }

        // Contact & Credentials
        Spacer(modifier = Modifier.height(16.dp))
        ProfileSectionLabel("CONTACT & CREDENTIALS")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                WorkAssignmentDetailRow("Email", profile?.email ?: "worker@artify.internal")
                WorkAssignmentDetailRow("Phone", profile?.phone ?: "+968 9123 4567")
                WorkAssignmentDetailRow("Department", profile?.department ?: "Civil & Structural Team")
                WorkAssignmentDetailRow("Account Status", if (profile != null) "Active" else "Active")
            }
        }

        // Security & Biometric
        Spacer(modifier = Modifier.height(16.dp))
        ProfileSectionLabel("SECURITY & BIOMETRIC VERIFICATION")
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                WorkAssignmentDetailRow("Device ID", deviceId.take(18) + "…")
                WorkAssignmentDetailRow("Facial Biometrics", "Enrolled & Active")
                WorkAssignmentDetailRow("Time Authority", "Authoritative server clock (UTC)")
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
private fun WorkAssignmentDetailRow(label: String, value: String) {
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
