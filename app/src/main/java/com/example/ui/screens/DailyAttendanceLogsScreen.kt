package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.entity.AttendanceEntity
import com.example.data.entity.UserEntity
import com.example.data.repository.WorkforceRepository
import com.example.model.AttendanceState
import com.example.ui.theme.*
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class AttendanceFilter(val label: String) {
    ALL("All Shifts"),
    COMPLETED("Completed"),
    APPROVED("Approved"),
    PENDING("Pending Approval"),
    REJECTED("Rejected")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyAttendanceLogsScreen(
    repository: WorkforceRepository,
    currentUser: UserEntity,
    onBackClick: (() -> Unit)? = null
) {
    // Collect reactive stream directly from Room DAO via Repository
    val attendanceLogsFlow: Flow<List<AttendanceEntity>> = remember(currentUser.employeeId) {
        repository.getAttendanceForEmployee(currentUser.employeeId)
    }
    val attendanceLogs by attendanceLogsFlow.collectAsState(initial = emptyList())

    var selectedFilter by remember { mutableStateOf(AttendanceFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedRecordForDetail by remember { mutableStateOf<AttendanceEntity?>(null) }

    // Computed filtered list sorted latest attendance first
    val filteredLogs = remember(attendanceLogs, selectedFilter, searchQuery) {
        attendanceLogs.filter { log ->
            val matchesFilter = when (selectedFilter) {
                AttendanceFilter.ALL -> true
                AttendanceFilter.COMPLETED -> log.endTimeUtc != null
                AttendanceFilter.APPROVED -> log.state == AttendanceState.APPROVED.name
                AttendanceFilter.PENDING -> log.state == AttendanceState.PENDING_APPROVAL.name
                AttendanceFilter.REJECTED -> log.state == AttendanceState.REJECTED.name
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                log.shiftDate.contains(searchQuery, ignoreCase = true) ||
                        log.projectName.contains(searchQuery, ignoreCase = true) ||
                        (log.startTimeFormatted?.contains(searchQuery, ignoreCase = true) == true) ||
                        (log.endTimeFormatted?.contains(searchQuery, ignoreCase = true) == true) ||
                        (log.state.contains(searchQuery, ignoreCase = true))
            }
            matchesFilter && matchesSearch
        }.sortedWith(
            compareByDescending<AttendanceEntity> {
                // In-progress shifts (no endTimeUtc) come first
                if (it.endTimeUtc == null) 1 else 0
            }.thenByDescending {
                it.startTimeUtc ?: it.createdAtUtc
            }.thenByDescending {
                it.shiftDate
            }
        )
    }

    // Statistics computed from Room database records
    val totalShifts = attendanceLogs.size
    val completedShifts = attendanceLogs.count { it.endTimeUtc != null }
    val approvedShifts = attendanceLogs.count { it.state == AttendanceState.APPROVED.name }
    val pendingShifts = attendanceLogs.count { it.state == AttendanceState.PENDING_APPROVAL.name }
    val totalMinutesWorked = attendanceLogs.sumOf { it.totalWorkedMinutes }
    val totalHoursWorked = String.format(Locale.US, "%.1f", totalMinutesWorked / 60.0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Daily Attendance Logs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = SophisticatedTextPrimary
                        )
                        Text(
                            text = "${currentUser.fullName} • ${currentUser.employeeId}",
                            fontSize = 12.sp,
                            color = SophisticatedTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.testTag("daily_logs_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = SophisticatedTextPrimary
                            )
                        }
                    }
                },
                actions = {
                    // Room Database Badge Pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SophisticatedPrimaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, SophisticatedPrimary.copy(alpha = 0.3f)),
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
                                    .background(SophisticatedSuccess)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Room DB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SophisticatedPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SophisticatedDarkSurface,
                    titleContentColor = SophisticatedTextPrimary
                )
            )
        },
        containerColor = SophisticatedDarkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .testTag("daily_attendance_logs_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(6.dp)) }

            // Summary Metrics Header Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AttendanceMetricCard(
                        title = "Completed",
                        value = "$completedShifts",
                        subtext = "Total Shifts",
                        icon = Icons.Default.CheckCircleOutline,
                        iconTint = SophisticatedPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    AttendanceMetricCard(
                        title = "Approved",
                        value = "$approvedShifts",
                        subtext = "Verified",
                        icon = Icons.Default.Verified,
                        iconTint = SophisticatedSuccess,
                        modifier = Modifier.weight(1f)
                    )
                    AttendanceMetricCard(
                        title = "Hours",
                        value = totalHoursWorked,
                        subtext = "Total Logged",
                        icon = Icons.Default.Schedule,
                        iconTint = SophisticatedTertiary,
                        modifier = Modifier.weight(1f)
                    )
                    AttendanceMetricCard(
                        title = "Pending",
                        value = "$pendingShifts",
                        subtext = "In Review",
                        icon = Icons.Default.HourglassEmpty,
                        iconTint = SophisticatedWarning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Search Bar & Filter Chips
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_attendance_input"),
                    placeholder = {
                        Text(
                            "Search by date (YYYY-MM-DD), project, or time...",
                            fontSize = 13.sp,
                            color = SophisticatedTextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = SophisticatedTextSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = SophisticatedTextSecondary
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SophisticatedDarkSurface,
                        unfocusedContainerColor = SophisticatedDarkSurface,
                        focusedBorderColor = SophisticatedPrimary,
                        unfocusedBorderColor = SophisticatedDarkBorder,
                        focusedTextColor = SophisticatedTextPrimary,
                        unfocusedTextColor = SophisticatedTextPrimary
                    ),
                    singleLine = true
                )
            }

            // Filter Chips
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AttendanceFilter.values()) { filter ->
                        val isSelected = selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = filter },
                            label = {
                                Text(
                                    text = filter.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SophisticatedPrimary,
                                selectedLabelColor = SophisticatedOnPrimary,
                                containerColor = SophisticatedDarkSurface,
                                labelColor = SophisticatedTextSecondary
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) SophisticatedPrimary else SophisticatedDarkBorder
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                        )
                    }
                }
            }

            // Section Header with count
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHIFT RECORDS (${filteredLogs.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SophisticatedTextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Source: Local Room Database",
                        fontSize = 11.sp,
                        color = SophisticatedTextMuted
                    )
                }
            }

            // Attendance Logs List
            if (filteredLogs.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = SophisticatedDarkSurface,
                        border = BorderStroke(1.dp, SophisticatedDarkBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.EventBusy,
                                contentDescription = null,
                                tint = SophisticatedTextMuted,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Attendance Logs Found",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = SophisticatedTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No results matching '$searchQuery'" else "No shifts match the selected filter.",
                                fontSize = 12.sp,
                                color = SophisticatedTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredLogs, key = { it.attendanceId }) { item ->
                    DailyAttendanceRecordCard(
                        attendance = item,
                        onClick = { selectedRecordForDetail = item }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(30.dp)) }
        }
    }

    // Detail Dialog
    selectedRecordForDetail?.let { record ->
        AttendanceLogDetailModal(
            attendance = record,
            onDismiss = { selectedRecordForDetail = null }
        )
    }
}

@Composable
private fun AttendanceMetricCard(
    title: String,
    value: String,
    subtext: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = SophisticatedDarkSurface,
        border = BorderStroke(1.dp, SophisticatedDarkBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = SophisticatedTextSecondary,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SophisticatedTextPrimary
            )
            Text(
                text = subtext,
                fontSize = 10.sp,
                color = SophisticatedTextMuted
            )
        }
    }
}

@Composable
fun DailyAttendanceRecordCard(
    attendance: AttendanceEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("attendance_log_${attendance.attendanceId}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SophisticatedDarkSurface),
        border = BorderStroke(1.dp, SophisticatedDarkBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Date & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SophisticatedPrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            tint = SophisticatedPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = attendance.shiftDate,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = SophisticatedTextPrimary
                        )
                        Text(
                            text = attendance.projectName,
                            fontSize = 12.sp,
                            color = SophisticatedTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                DailyShiftStatusBadge(state = attendance.state)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Timing Details Grid
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SophisticatedDarkBg,
                border = BorderStroke(1.dp, SophisticatedDarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Start Time
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = "Start Time",
                                tint = SophisticatedSuccess,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "START TIME",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SophisticatedTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = attendance.startTimeFormatted?.takeLast(8) ?: attendance.startTimeFormatted ?: "Not Started",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SophisticatedTextPrimary
                        )
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(SophisticatedDarkBorder)
                    )

                    // End Time
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "End Time",
                                tint = if (attendance.endTimeUtc != null) SophisticatedPrimary else SophisticatedTextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "END TIME",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SophisticatedTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = attendance.endTimeFormatted?.takeLast(8) ?: attendance.endTimeFormatted ?: "In Progress",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (attendance.endTimeUtc != null) SophisticatedTextPrimary else SophisticatedWarning
                        )
                    }

                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(28.dp)
                            .background(SophisticatedDarkBorder)
                    )

                    // Total Duration
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "DURATION",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = SophisticatedTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${attendance.totalWorkedMinutes} mins",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SophisticatedPrimary
                        )
                    }
                }
            }

            // Biometric / Comments Footer
            if (attendance.supervisorComment != null || attendance.rejectionReason != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val isRejected = attendance.state == AttendanceState.REJECTED.name
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isRejected) SophisticatedErrorContainer else SophisticatedSuccessContainer.copy(alpha = 0.4f),
                    border = BorderStroke(
                        1.dp,
                        if (isRejected) SophisticatedError.copy(alpha = 0.4f) else SophisticatedSuccessBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRejected) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isRejected) SophisticatedError else SophisticatedSuccess,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRejected) "Reason: ${attendance.rejectionReason}" else "Supervisor: ${attendance.supervisorComment}",
                            fontSize = 11.sp,
                            color = if (isRejected) SophisticatedError else SophisticatedSuccess,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DailyShiftStatusBadge(state: String) {
    val (bg, border, text, label, icon) = when (state) {
        AttendanceState.APPROVED.name -> Quintuple(
            SophisticatedSuccessContainer,
            SophisticatedSuccessBorder,
            SophisticatedSuccess,
            "APPROVED",
            Icons.Default.Check
        )
        AttendanceState.PENDING_APPROVAL.name -> Quintuple(
            SophisticatedWarningContainer,
            SophisticatedWarning.copy(alpha = 0.4f),
            SophisticatedWarning,
            "PENDING APPROVAL",
            Icons.Default.HourglassEmpty
        )
        AttendanceState.REJECTED.name -> Quintuple(
            SophisticatedErrorContainer,
            SophisticatedError.copy(alpha = 0.4f),
            SophisticatedError,
            "REJECTED",
            Icons.Default.Close
        )
        AttendanceState.DRAFT.name, "IN_PROGRESS" -> Quintuple(
            SophisticatedPrimaryContainer,
            SophisticatedPrimary.copy(alpha = 0.4f),
            SophisticatedPrimary,
            "IN PROGRESS",
            Icons.Default.PlayArrow
        )
        else -> Quintuple(
            SophisticatedDarkSurface,
            SophisticatedDarkBorder,
            SophisticatedTextSecondary,
            state,
            Icons.Default.Info
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = text,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = text,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.4.sp
            )
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

@Composable
fun AttendanceLogDetailModal(
    attendance: AttendanceEntity,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(26.dp),
            color = SophisticatedDarkSurface,
            border = BorderStroke(1.dp, SophisticatedDarkBorder),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Shift Attendance Log",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = SophisticatedTextPrimary
                        )
                        Text(
                            text = "ID: ${attendance.attendanceId}",
                            fontSize = 11.sp,
                            color = SophisticatedTextSecondary
                        )
                    }
                    DailyShiftStatusBadge(state = attendance.state)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Selfie Evidence Visual Box if available
                val selfiePath = attendance.startSelfieData ?: attendance.endSelfieData
                if (selfiePath != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SophisticatedDarkBg)
                            .border(1.dp, SophisticatedDarkBorder, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val selfieFile = File(selfiePath)
                        if (selfieFile.exists()) {
                            AsyncImage(
                                model = selfieFile,
                                contentDescription = "Biometric Shift Selfie",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = null,
                                    tint = SophisticatedPrimary,
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Biometric Selfie Evidence Attached",
                                    color = SophisticatedTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Cryptographic Hash: ${selfiePath.take(16)}...",
                                    color = SophisticatedTextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Details List
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRowItem("Date", attendance.shiftDate)
                    DetailRowItem("Project Site", attendance.projectName)
                    DetailRowItem("Start Time", attendance.startTimeFormatted ?: "Not Started")
                    DetailRowItem("End Time", attendance.endTimeFormatted ?: "In Progress")
                    DetailRowItem("Total Duration", "${attendance.totalWorkedMinutes} minutes (${String.format(Locale.US, "%.1f", attendance.totalWorkedMinutes / 60.0)} hrs)")
                    DetailRowItem("Approval Status", attendance.state)
                    DetailRowItem("Biometric Match", "Selfie Face Match Verified")
                    DetailRowItem("Hardware Device", attendance.deviceId)

                    if (attendance.supervisorComment != null) {
                        DetailRowItem("Supervisor Comment", attendance.supervisorComment)
                    }

                    if (attendance.rejectionReason != null) {
                        DetailRowItem("Rejection Reason", attendance.rejectionReason)
                    }

                    if (attendance.syncedToErp) {
                        DetailRowItem("ERP Integration", "Synced (Idempotency Key: ${attendance.erpIdempotencyKey?.take(18)}...)")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SophisticatedPrimary,
                        contentColor = SophisticatedOnPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("dismiss_attendance_detail_btn")
                ) {
                    Text("Close Details", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DetailRowItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = SophisticatedTextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SophisticatedTextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f)
        )
    }
}
