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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.repository.BackendAuthRepository
import com.example.data.repository.BackendWorkforceRepository
import com.example.data.repository.WorkforceRepository
import com.example.data.sync.NetworkMonitor
import com.example.data.sync.OfflineCache
import com.example.data.sync.RealSyncManager
import com.example.location.LocationHelper
import com.example.model.UserRole
import com.example.notifications.FcmNotificationManager
import com.example.security.SecureSessionStore
import com.example.ui.components.DemoModeBanner
import com.example.ui.screens.RealAuthEntryScreen
import com.example.ui.screens.RealSupervisorDashboardScreen
import com.example.ui.screens.RealWorkerDashboardScreen
import com.example.ui.screens.SupervisorDashboardScreen
import com.example.ui.screens.WorkerDashboardScreen
import com.example.ui.theme.ArtifyTheme
import com.example.ui.theme.ThemePreferences
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.RealAuthScreenState
import com.example.ui.viewmodel.RealAuthViewModel
import com.example.ui.viewmodel.RealSupervisorViewModel
import com.example.ui.viewmodel.RealWorkerViewModel
import com.example.ui.viewmodel.SupervisorViewModel
import com.example.ui.viewmodel.WorkerViewModel
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

    // Demo Mode's database/repository/view model are intentionally NOT created until a
    // demo account is actually requested. Building them eagerly here would seed the local
    // Demo database and start the Firestore sync manager on every single app launch, even
    // for a user who only ever uses Real Mode.
    var demoAuthViewModel by remember { mutableStateOf<AuthViewModel?>(null) }
    var demoRepository by remember { mutableStateOf<WorkforceRepository?>(null) }
    val ensureDemoAuthViewModel: () -> AuthViewModel = ensure@{
        demoAuthViewModel?.let { return@ensure it }
        val db = AppDatabase.getInstance(context)
        val repo = WorkforceRepository(db, context.applicationContext)
        val vm = AuthViewModel(repo)
        demoRepository = repo
        demoAuthViewModel = vm
        vm
    }
    val demoAuthState = demoAuthViewModel?.uiState?.collectAsState()?.value

    val signedInEmployee = realAuthState.signedInEmployee

    // Register FCM notifications for authenticated Real Mode employee
    LaunchedEffect(signedInEmployee?.id) {
        signedInEmployee?.let { emp ->
            FcmNotificationManager.registerEmployeeForPushNotifications(
                context = context,
                employeeId = emp.employeeCode,
                role = emp.role,
                fullName = emp.fullName
            )
        }
    }

    when {
        realAuthState.screen == RealAuthScreenState.SIGNED_IN && signedInEmployee != null -> {
            val backendWorkforceRepository = remember(signedInEmployee.id) {
                BackendWorkforceRepository(realAuthRepository, SecureSessionStore.getInstance(context))
            }
            if (signedInEmployee.role == "SUPERVISOR" || signedInEmployee.role == "ADMIN") {
                val supervisorViewModel = remember(signedInEmployee.id) { RealSupervisorViewModel(backendWorkforceRepository) }
                RealSupervisorDashboardScreen(
                    viewModel = supervisorViewModel,
                    supervisorName = signedInEmployee.fullName,
                    supervisorCode = signedInEmployee.employeeCode,
                    onLogout = { realAuthViewModel.logout() }
                )
            } else {
                val locationHelper = remember { LocationHelper(context) }
                val syncManager = remember(signedInEmployee.id) {
                    RealSyncManager(context, backendWorkforceRepository, NetworkMonitor(context), signedInEmployee.id)
                }
                val offlineCache = remember { OfflineCache(context) }
                val workerViewModel = remember(signedInEmployee.id) {
                    RealWorkerViewModel(backendWorkforceRepository, locationHelper, syncManager, offlineCache, signedInEmployee.id)
                }
                RealWorkerDashboardScreen(
                    viewModel = workerViewModel,
                    employeeName = signedInEmployee.fullName,
                    employeeCode = signedInEmployee.employeeCode,
                    onLogout = { realAuthViewModel.logout() }
                )
            }
        }
        demoAuthState?.currentUser != null -> {
            ArtifyDemoModeRoot(repository = demoRepository!!, authViewModel = demoAuthViewModel!!, user = demoAuthState.currentUser)
        }
        else -> {
            RealAuthEntryScreen(
                realAuthViewModel = realAuthViewModel,
                onRequestDemoAuthViewModel = ensureDemoAuthViewModel,
                onSignedIn = {}
            )
        }
    }
}

@Composable
private fun ArtifyDemoModeRoot(repository: WorkforceRepository, authViewModel: AuthViewModel, user: com.example.data.entity.UserEntity) {
    val context = LocalContext.current
    val locationHelper = remember { LocationHelper(context) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        repository.seedInitialDataIfEmpty()
    }

    // Dynamic Permission Launcher for Location, Camera, and Notifications
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

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

    // Register FCM notifications for authenticated user
    LaunchedEffect(user.userId) {
        FcmNotificationManager.registerUserForPushNotifications(context, user)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DemoModeBanner()
        Box(modifier = Modifier.weight(1f)) {
            when (user.role) {
                UserRole.SUPERVISOR.name -> {
                    val supervisorViewModel = remember(user.userId) {
                        SupervisorViewModel(repository, user)
                    }
                    SupervisorDashboardScreen(
                        supervisorViewModel = supervisorViewModel,
                        onLogoutClick = { authViewModel.logout() }
                    )
                }
                else -> {
                    // Worker or Staff
                    val workerViewModel = remember(user.userId) {
                        WorkerViewModel(repository, user)
                    }

                    // Feed device location if available
                    LaunchedEffect(hasLocationPermission) {
                        if (hasLocationPermission) {
                            coroutineScope.launch {
                                val loc = locationHelper.getCurrentLocation()
                                if (loc != null) {
                                    workerViewModel.onDeviceLocationReceived(loc)
                                }
                            }
                        }
                    }

                    WorkerDashboardScreen(
                        workerViewModel = workerViewModel,
                        repository = repository,
                        locationHelper = locationHelper,
                        onLogoutClick = { authViewModel.logout() }
                    )
                }
            }
        }
    }
}
