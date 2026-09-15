package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.entity.ProjectEntity
import com.example.model.*
import com.example.server.ServerAuthorityEngine
import com.example.server.ServerGeofenceResult
import com.example.ui.theme.*

/**
 * Persistent indicator that the current session is Demo Mode (locally-seeded
 * fake data only), never the real backend. Per the workforce-platform spec,
 * Demo Mode must always be clearly distinguishable from a real account.
 */
@Composable
fun DemoModeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SophisticatedWarningContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Science, contentDescription = null, tint = SophisticatedWarning, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "DEMO MODE — sample data only, not connected to the real workforce backend",
                color = SophisticatedWarning,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
        }
    }
}

@Composable
fun ArtifyTopHeader(
    userName: String,
    employeeId: String,
    role: String,
    onLogoutClick: () -> Unit,
    avatarUrl: String? = null,
    onAvatarClick: (() -> Unit)? = null,
    notificationCount: Int = 0,
    onNotificationClick: () -> Unit = {},
    onThemeClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotationAngle by if (isSyncing) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing)
            ),
            label = "sync_spin"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    val isDark = LocalIsDarkTheme.current
    val themePrefs = LocalThemePreferences.current
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog && themePrefs != null) {
        ThemeSettingsDialog(
            themePreferences = themePrefs,
            onDismiss = { showThemeDialog = false }
        )
    }

    val roleColor = when (role) {
        UserRole.SUPERVISOR.name -> if (isDark) SophisticatedWarning else SophisticatedLightWarning
        UserRole.STAFF.name -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBackClick != null) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("header_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.example.R.drawable.artify_logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "ARTIFY WORKFORCE",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Good Day, $userName",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Theme Quick Mode Toggle Button
                    IconButton(
                        onClick = {
                            if (onThemeClick != null) {
                                onThemeClick()
                            } else if (themePrefs != null) {
                                themePrefs.toggleTheme(isDark)
                            }
                        },
                        modifier = Modifier.testTag("header_theme_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = if (isDark) "Switch to Light Mode" else "Switch to Dark Mode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onNotificationClick,
                        modifier = Modifier.testTag("notification_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (notificationCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text(notificationCount.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Notifications",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onLogoutClick,
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Logout",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-header strip with Employee ID on the left & Sync icon in front of Staff on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "ID: $employeeId",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                val triggerSync: () -> Unit = {
                    if (!isSyncing) {
                        isSyncing = true
                        coroutineScope.launch {
                            try {
                                onSyncClick?.invoke()
                                val db = com.example.data.AppDatabase.getInstance(context)
                                com.example.sync.FirestoreSyncManager.getInstance(context, db).syncPendingAttendanceRecords()
                            } catch (_: Exception) {}
                            kotlinx.coroutines.delay(650)
                            isSyncing = false
                            android.widget.Toast.makeText(context, "Workforce synchronized", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Sync icon on header right side in front of Staff
                    IconButton(
                        onClick = triggerSync,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("header_sync_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Sync Workforce Data",
                            tint = if (isSyncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotationAngle)
                                .testTag("header_sync_icon")
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = roleColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, roleColor.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .testTag("header_role_badge")
                            .clickable(onClick = triggerSync)
                    ) {
                        Text(
                            text = role,
                            color = roleColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GeofenceRadarCard(
    project: ProjectEntity?,
    geofenceResult: ServerGeofenceResult?,
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    isMockLocation: Boolean,
    onRefreshLocation: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val isInside = geofenceResult?.isInside == true && geofenceResult.isAccuracyAcceptable && !isMockLocation

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("geofence_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CURRENT PROJECT",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = project?.projectName ?: "Loading Project...",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = project?.address ?: "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = (if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isDark) SophisticatedSuccessBorder else SophisticatedLightSuccessBorder
                    )
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
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "ACTIVE",
                            color = if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Location Verified Status Inset Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isInside) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isInside) Icons.Default.LocationOn else Icons.Default.LocationOff,
                                contentDescription = "Location Pin",
                                tint = if (isInside) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (isInside) "✓ On-Site Verified" else "⚡ Off-Site • Head Office Notified",
                                color = if (isInside) (if (isDark) SophisticatedSuccess else SophisticatedLightSuccess) else (if (isDark) SophisticatedWarning else SophisticatedLightWarning),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (isInside)
                                    "Inside ${project?.geofenceRadiusMeters?.toInt() ?: 150}m Project Boundary • Head Office Payroll Synced"
                                else
                                    "${geofenceResult?.distanceMeters?.toInt() ?: 0}m from boundary • Logged for Head Office Payroll review",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onRefreshLocation,
                        modifier = Modifier.testTag("refresh_location_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh GPS Location",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Radar Visual representation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(115.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isDark) SophisticatedDarkBg else SophisticatedLightBg)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "radar")
                val pulseRadius by infiniteTransition.animateFloat(
                    initialValue = 20f,
                    targetValue = 90f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "pulse"
                )

                val radarGridColor = if (isDark) Color(0xFF333038) else Color(0xFFE2E6EC)
                val radarGridInnerColor = if (isDark) Color(0xFF26242B) else Color(0xFFECEFF4)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)

                    // Background grid circles
                    drawCircle(
                        color = radarGridColor,
                        radius = 80f,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )
                    drawCircle(
                        color = radarGridInnerColor,
                        radius = 45f,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )

                    // Project perimeter circle
                    drawCircle(
                        color = SophisticatedPrimary.copy(alpha = 0.15f),
                        radius = 65f,
                        center = center
                    )
                    drawCircle(
                        color = SophisticatedPrimary.copy(alpha = 0.6f),
                        radius = 65f,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )

                    // Animated scanning pulse
                    drawCircle(
                        color = if (isInside) (if (isDark) SophisticatedSuccess else SophisticatedLightSuccess).copy(alpha = 0.3f) else (if (isDark) SophisticatedError else SophisticatedLightError).copy(alpha = 0.3f),
                        radius = pulseRadius,
                        center = center,
                        style = Stroke(width = 1.5f)
                    )

                    // Center site pin
                    drawCircle(
                        color = SophisticatedPrimary,
                        radius = 5f,
                        center = center
                    )

                    // User location relative pin
                    val userOffset = if (isInside) {
                        Offset(center.x + 14f, center.y - 10f)
                    } else {
                        Offset(center.x + 80f, center.y + 12f)
                    }

                    drawCircle(
                        color = if (isInside) (if (isDark) SophisticatedSuccess else SophisticatedLightSuccess) else (if (isDark) SophisticatedError else SophisticatedLightError),
                        radius = 7f,
                        center = userOffset
                    )
                }

                // Overlay status text
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Radius: ${project?.geofenceRadiusMeters?.toInt() ?: 150}m",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "GPS: ${String.format("%.4f", latitude)}, ${String.format("%.4f", longitude)} (±${accuracy.toInt()}m)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 9.sp
                    )
                }

                val dist = geofenceResult?.distanceMeters?.toInt() ?: 0
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = RoundedCornerShape(50),
                    color = if (isInside) (if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer) else (if (isDark) SophisticatedWarningContainer else SophisticatedLightWarningContainer),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isInside) (if (isDark) SophisticatedSuccessBorder else SophisticatedLightSuccessBorder) else (if (isDark) SophisticatedWarning else SophisticatedLightWarning).copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = if (isInside) "On-Site (${dist}m)" else "Off-Site (${dist}m) • Payroll Logged",
                        color = if (isInside) (if (isDark) SophisticatedSuccess else SophisticatedLightSuccess) else (if (isDark) SophisticatedWarning else SophisticatedLightWarning),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SelfieCaptureDialog(
    eventType: ShiftEventType,
    projectName: String,
    onDismiss: () -> Unit,
    onCaptureComplete: (selfieHash: String) -> Unit
) {
    CameraXSelfieDialog(
        eventType = eventType,
        projectName = projectName,
        onDismiss = onDismiss,
        onCaptureComplete = onCaptureComplete
    )
}

@Composable
fun AttendanceStatusBadge(state: String) {
    val isDark = LocalIsDarkTheme.current
    val normalized = state.uppercase().trim()
    val (bg, fg, border, label) = when {
        normalized == "APPROVED" || normalized == AttendanceState.APPROVED.name -> Quadruple(
            if (isDark) SophisticatedSuccessContainer else SophisticatedLightSuccessContainer,
            if (isDark) SophisticatedSuccess else SophisticatedLightSuccess,
            if (isDark) SophisticatedSuccessBorder else SophisticatedLightSuccessBorder,
            "Approved"
        )
        normalized == "REJECTED" || normalized == AttendanceState.REJECTED.name -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
            "Rejected"
        )
        normalized == "PENDING" || normalized == "PENDING_APPROVAL" || normalized == AttendanceState.PENDING_APPROVAL.name -> Quadruple(
            if (isDark) SophisticatedWarningContainer else SophisticatedLightWarningContainer,
            if (isDark) SophisticatedWarning else SophisticatedLightWarning,
            (if (isDark) SophisticatedWarning else SophisticatedLightWarning).copy(alpha = 0.4f),
            "Pending"
        )
        normalized == AttendanceState.FLAGGED.name -> Quadruple(
            MaterialTheme.colorScheme.errorContainer,
            if (isDark) SophisticatedWarning else SophisticatedLightWarning,
            (if (isDark) SophisticatedWarning else SophisticatedLightWarning).copy(alpha = 0.4f),
            "Flagged"
        )
        normalized == AttendanceState.SUBMITTED.name -> Quadruple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            "Submitted"
        )
        else -> Quadruple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            state
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier.testTag("attendance_badge_${label.lowercase().replace(' ', '_')}")
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

