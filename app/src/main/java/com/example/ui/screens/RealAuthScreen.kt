package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import androidx.compose.ui.unit.sp
import com.example.model.UserRole
import com.example.network.BackendEmployee
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.RealAuthScreenState
import com.example.ui.viewmodel.RealAuthViewModel

/** Entry point shown when no cached device session exists. */
@Composable
fun RealAuthEntryScreen(
    realAuthViewModel: RealAuthViewModel,
    onRequestDemoAuthViewModel: () -> AuthViewModel,
    onSignedIn: (BackendEmployee) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by realAuthViewModel.uiState.collectAsState()

    LaunchedEffect(uiState.signedInEmployee) {
        uiState.signedInEmployee?.let { onSignedIn(it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        when (uiState.screen) {
            RealAuthScreenState.CHECKING_CACHED_SESSION, RealAuthScreenState.SIGNED_IN -> {
                // Nothing to render here — the caller switches away to
                // RealAccountLandingScreen once signedInEmployee is set.
            }
            RealAuthScreenState.PIN_LOGIN -> PinLoginContent(
                employeeName = uiState.cachedEmployeeName,
                isLoading = uiState.isLoading,
                errorMessage = uiState.errorMessage,
                onSubmitPin = { realAuthViewModel.loginWithPin(it) },
                onUseDifferentAccount = { realAuthViewModel.switchToRegisterInstead() }
            )
            RealAuthScreenState.CIVIL_ID_REGISTER -> CivilIdRegisterContent(
                isLoading = uiState.isLoading,
                errorMessage = uiState.errorMessage,
                notEligible = uiState.notEligibleForRealAccount,
                onSubmit = { civilId, pin -> realAuthViewModel.registerWithCivilId(civilId, pin) },
                onRequestDemoAuthViewModel = onRequestDemoAuthViewModel
            )
        }
    }
}

@Composable
private fun CivilIdRegisterContent(
    isLoading: Boolean,
    errorMessage: String?,
    notEligible: Boolean,
    onSubmit: (civilId: String, pin: String) -> Unit,
    onRequestDemoAuthViewModel: () -> AuthViewModel
) {
    var civilId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var showDemoPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))
        Image(
            painter = painterResource(id = R.drawable.artify_logo_mark),
            contentDescription = "Artify",
            modifier = Modifier.size(84.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text("VERIFY YOUR CIVIL ID", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "One-time verification against your employer's workforce roster. After this, you'll only need your 4-digit PIN on this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        errorMessage?.let { err ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (notEligible) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
            ) {
                Text(err, modifier = Modifier.padding(12.dp), fontSize = 12.5.sp, color = if (notEligible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        OutlinedTextField(
            value = civilId,
            onValueChange = { civilId = it.filter { c -> c.isDigit() } },
            label = { Text("Civil ID Number") },
            leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4) pin = it.filter { c -> c.isDigit() } },
            label = { Text("Choose a 4-digit PIN") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 4) confirmPin = it.filter { c -> c.isDigit() } },
            label = { Text("Confirm PIN") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(18.dp))

        val pinsMatch = pin.length == 4 && pin == confirmPin
        Button(
            onClick = { onSubmit(civilId, pin) },
            enabled = !isLoading && civilId.isNotBlank() && pinsMatch,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(50)
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text("VERIFY & CONTINUE", fontWeight = FontWeight.Bold)
        }
        if (pin.length == 4 && confirmPin.length == 4 && !pinsMatch) {
            Text("PINs don't match.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(modifier = Modifier.height(22.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        DemoModeEntryCard(onClick = { showDemoPicker = true })
        Spacer(modifier = Modifier.height(30.dp))
    }

    if (showDemoPicker) {
        DemoAccountPickerDialog(
            onRequestDemoAuthViewModel = onRequestDemoAuthViewModel,
            onDismiss = { showDemoPicker = false }
        )
    }
}

@Composable
private fun DemoModeEntryCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Not on the roster yet? Try the Demo", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Text("Explore every screen with a sample account", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DemoAccountPickerDialog(onRequestDemoAuthViewModel: () -> AuthViewModel, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Choose a Demo Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Sample data only — nothing here touches the real backend", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Spacer(modifier = Modifier.height(14.dp))
                DemoAccountRow(
                    icon = Icons.Default.Shield, name = "Artify Staff Admin", role = "Supervisor Admin",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { onRequestDemoAuthViewModel().quickLoginAsEmail("artifystaff@gmail.com") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DemoAccountRow(
                    icon = Icons.Default.Engineering, name = "Ahmed Ali Al-Balushi", role = "Worker",
                    color = MaterialTheme.colorScheme.secondary,
                    onClick = { onRequestDemoAuthViewModel().quickLoginAs(UserRole.WORKER) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DemoAccountRow(
                    icon = Icons.Default.Work, name = "Fatima Al-Harthy", role = "Staff",
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = { onRequestDemoAuthViewModel().quickLoginAs(UserRole.STAFF) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                DemoAccountRow(
                    icon = Icons.Default.AdminPanelSettings, name = "Tariq Al-Said", role = "Supervisor",
                    color = MaterialTheme.colorScheme.error,
                    onClick = { onRequestDemoAuthViewModel().quickLoginAs(UserRole.SUPERVISOR) }
                )
            }
        }
    }
}

@Composable
private fun DemoAccountRow(icon: ImageVector, name: String, role: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(role, fontSize = 10.5.sp, color = color)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinLoginContent(
    employeeName: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onSubmitPin: (String) -> Unit,
    onUseDifferentAccount: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    // Requirement 2: When an incorrect PIN error occurs, automatically clear all entered digits/state
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            pin = ""
        }
    }

    LaunchedEffect(pin) {
        if (pin.length == 4 && !isLoading) onSubmitPin(pin)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.artify_logo_mark),
            contentDescription = "Artify",
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Welcome back${employeeName?.let { ", ${it.substringBefore(" ")}" } ?: ""}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Enter your PIN to continue", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (index < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(14.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (isLoading) {
            Spacer(modifier = Modifier.height(14.dp))
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "back")
        digits.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { key ->
                    when {
                        key.isEmpty() -> Spacer(modifier = Modifier.size(64.dp))
                        key == "back" -> Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .clickable(enabled = pin.isNotEmpty() && !isLoading) { pin = pin.dropLast(1) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        else -> Box(
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable(enabled = pin.length < 4 && !isLoading) { pin += key },
                            contentAlignment = Alignment.Center
                        ) { Text(key, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        TextButton(onClick = onUseDifferentAccount) {
            Text("Not you? Verify a different Civil ID")
        }
    }
}
