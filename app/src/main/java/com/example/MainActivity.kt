package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.repository.BackendAuthRepository
import com.example.data.repository.BackendWorkforceRepository
import com.example.data.repository.DemoWorkforceRepository
import com.example.data.repository.IWorkforceRepository
import com.example.data.repository.SupabaseStorageService
import com.example.data.repository.WorkforceRepository
import com.example.data.sync.NetworkMonitor
import com.example.data.sync.OfflineCache
import com.example.data.sync.RealSyncManager
import com.example.location.LocationHelper
import com.example.notifications.FcmNotificationManager
import com.example.security.SecureSessionStore
import com.example.ui.components.DemoModeBanner
import com.example.ui.screens.RealAuthEntryScreen
import com.example.ui.screens.RealSupervisorDashboardScreen
import com.example.ui.screens.RealWorkerDashboardScreen
import com.example.ui.theme.ArtifyTheme
import com.example.ui.theme.ThemePreferences
import com.example.ui.viewmodel.RealAuthScreenState
import com.example.ui.viewmodel.RealAuthViewModel
import com.example.ui.viewmodel.RealSupervisorViewModel
import com.example.ui.viewmodel.RealWorkerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the previous run crashed, ArtifyApplication's handler wrote the details to this
        // file before the process died. Show it now instead of the normal UI, since trying to
        // launch a new Activity from inside the crash handler itself is unreliable on some
        // OEM skins (the process may already be mid-teardown at that point).
        val crashFile = File(filesDir, ArtifyApplication.CRASH_FILE_NAME)
        if (crashFile.exists()) {
            val details = try { crashFile.readText() } finally { crashFile.delete() }
            startActivity(
                android.content.Intent(this, CrashReportActivity::class.java)
                    .putExtra(CrashReportActivity.EXTRA_DETAILS, details)
            )
            finish()
            return
        }

        enableEdgeToEdge()
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                val app = com.google.firebase.FirebaseApp.initializeApp(this)
                if (app == null) {
                    val options = com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId(packageName)
                        .setProjectId("artify-workforce-app")
                        .setApiKey("AIzaSyDummyKeyForLocalOfflinePersistenceOnly")
                        .build()
                    com.google.firebase.FirebaseApp.initializeApp(this, options)
                }
            }
        } catch (_: Exception) {}
        FcmNotificationManager.createNotificationChannels(this)
        setContent {
            val themePreferences = remember { ThemePreferences.getInstance(applicationContext) }
            val themeSettings by themePreferences.settings.collectAsState()

            ArtifyTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.dynamicColor,
                accentPalette = themeSettings.accentPalette,
                themePreferences = themePreferences
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    ArtifyAppRoot()
                }
            }
        }
    }
}

@Composable
fun ArtifyAppRoot() {
    val context = LocalContext.current

    // Real Civil-ID-then-PIN flow against the Artify Central Backend. This is
    // the single entry point: first-time devices see Civil ID registration,
    // returning devices see PIN login, and a "Quick Demo Access" section at
    // the bottom of the registration screen drops straight into Demo Mode.
    val realAuthRepository = remember { BackendAuthRepository(SecureSessionStore.getInstance(context)) }
    val realAuthViewModel = remember { RealAuthViewModel(realAuthRepository) }
    val realAuthState by realAuthViewModel.uiState.collectAsState()

    // Dynamic Permission Launcher for Location, Camera, and Push Notifications
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled */ }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    val signedInEmployee = realAuthState.signedInEmployee

    // Register FCM notifications for authenticated Real Mode employee
    LaunchedEffect(signedInEmployee?.id) {
        signedInEmployee?.let { emp ->
            if (!emp.isDemo) {
                FcmNotificationManager.registerEmployeeForPushNotifications(
                    context = context,
                    employeeId = emp.employeeCode,
                    role = emp.role,
                    fullName = emp.fullName
                )
            }
        }
    }

    when {
        realAuthState.screen == RealAuthScreenState.SIGNED_IN && signedInEmployee != null -> {
            val workforceRepository: IWorkforceRepository = if (signedInEmployee.isDemo) {
                remember(signedInEmployee.id) {
                    val db = AppDatabase.getInstance(context)
                    val demoRepo = WorkforceRepository(db, context.applicationContext)
                    kotlinx.coroutines.runBlocking { demoRepo.seedInitialDataIfEmpty() }
                    DemoWorkforceRepository(db, signedInEmployee.id, context)
                }
            } else {
                remember(signedInEmployee.id) {
                    BackendWorkforceRepository(realAuthRepository, SecureSessionStore.getInstance(context))
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                if (signedInEmployee.isDemo) {
                    DemoModeBanner()
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (signedInEmployee.role == "SUPERVISOR" || signedInEmployee.role == "ADMIN") {
                        val supervisorViewModel = remember(signedInEmployee.id) { RealSupervisorViewModel(workforceRepository) }
                        RealSupervisorDashboardScreen(
                            viewModel = supervisorViewModel,
                            supervisorName = signedInEmployee.fullName,
                            supervisorCode = signedInEmployee.employeeCode,
                            onLogout = { realAuthViewModel.logout() }
                        )
                    } else {
                        val locationHelper = remember { LocationHelper(context) }
                        val syncManager = remember(signedInEmployee.id) {
                            RealSyncManager(context, workforceRepository, NetworkMonitor(context), signedInEmployee.id)
                        }
                        val offlineCache = remember { OfflineCache(context) }
                        val storageService = remember { SupabaseStorageService(context) }
                        val workerViewModel = remember(signedInEmployee.id) {
                            RealWorkerViewModel(workforceRepository, locationHelper, syncManager, offlineCache, signedInEmployee.id, storageService)
                        }
                        RealWorkerDashboardScreen(
                            viewModel = workerViewModel,
                            employeeName = signedInEmployee.fullName,
                            employeeCode = signedInEmployee.employeeCode,
                            onLogout = { realAuthViewModel.logout() }
                        )
                    }
                }
            }
        }
        else -> {
            RealAuthEntryScreen(
                realAuthViewModel = realAuthViewModel,
                onSignedIn = {}
            )
        }
    }
}
