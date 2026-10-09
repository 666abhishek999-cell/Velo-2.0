package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AudioSourceType
import com.example.model.CaptureMode
import com.example.service.ScreenCaptureService
import com.example.ui.MainTab
import com.example.ui.MainViewModel
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMatrix
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.VeloStreamTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VeloStreamTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val stats by viewModel.liveStats.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val hardwareCaps by viewModel.hardwareCaps.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val testResult by viewModel.connectionTestResult.collectAsStateWithLifecycle()

    var pendingCaptureMode by remember { mutableStateOf<CaptureMode?>(null) }

    // MediaProjection permission launcher
    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val mode = pendingCaptureMode ?: CaptureMode.STREAM_ONLY
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_CAPTURE_MODE, mode.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Toast.makeText(context, "Screen capture permission is required to stream or record", Toast.LENGTH_SHORT).show()
        }
        pendingCaptureMode = null
    }

    // Permission launcher for audio and notification
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val mode = pendingCaptureMode
        if (mode != null) {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    val requestCapture = { mode: CaptureMode ->
        pendingCaptureMode = mode
        val neededPerms = mutableListOf<String>()

        if (config.audioSource == AudioSourceType.MICROPHONE || config.audioSource == AudioSourceType.INTERNAL_PLUS_MIC) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.RECORD_AUDIO)
            }
            if (config.audioInputDevice == com.example.model.AudioInputDevice.BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    neededPerms.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Overlay permission check if user enabled floating overlay
        if (config.showFloatingOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Grant 'Display over other apps' to use Floating Gaming HUD", Toast.LENGTH_LONG).show()
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(overlayIntent)
        }

        if (neededPerms.isNotEmpty()) {
            permissionLauncher.launch(neededPerms.toTypedArray())
        } else {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    val stopCapture = {
        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    // Handle back button: if in secondary tabs, return to Studio tab
    BackHandler(enabled = currentTab != MainTab.STUDIO) {
        viewModel.selectTab(MainTab.STUDIO)
    }

    val supportEval = viewModel.evaluateCurrentSupport()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "VELO",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = ElectricCyan,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "STREAM",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )

                        if (stats.isStreaming || stats.isRecording) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(NeonCrimson)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (stats.isStreaming && stats.isRecording) "LIVE+REC"
                                    else if (stats.isStreaming) "LIVE" else "REC",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = currentTab == MainTab.STUDIO,
                    onClick = { viewModel.selectTab(MainTab.STUDIO) },
                    icon = { Icon(Icons.Default.Radio, contentDescription = "Studio") },
                    label = { Text("Studio") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = DarkSurfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_studio")
                )

                NavigationBarItem(
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { viewModel.selectTab(MainTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = DarkSurfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_settings")
                )

                NavigationBarItem(
                    selected = currentTab == MainTab.DIAGNOSTICS,
                    onClick = { viewModel.selectTab(MainTab.DIAGNOSTICS) },
                    icon = { Icon(Icons.Default.Memory, contentDescription = "Diagnostics") },
                    label = { Text("Hardware") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = DarkSurfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_diagnostics")
                )

                NavigationBarItem(
                    selected = currentTab == MainTab.RECORDINGS,
                    onClick = { viewModel.selectTab(MainTab.RECORDINGS) },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "Recordings") },
                    label = { Text("Clips") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        indicatorColor = DarkSurfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.testTag("tab_recordings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            when (currentTab) {
                MainTab.STUDIO -> StudioScreen(
                    config = config,
                    stats = stats,
                    supportEval = supportEval,
                    onStartCapture = requestCapture,
                    onStopCapture = { stopCapture() },
                    onNavigateTab = { viewModel.selectTab(it) },
                    onUpdateConfig = { viewModel.updateConfig(it) }
                )
                MainTab.SETTINGS -> SettingsScreen(
                    config = config,
                    supportEval = supportEval,
                    hardwareCaps = hardwareCaps,
                    isTestingConnection = isTesting,
                    testResult = testResult,
                    onUpdateConfig = { viewModel.updateConfig(it) },
                    onTestConnection = { viewModel.testRtmpConnection() },
                    onClearTestResult = { viewModel.clearTestResult() }
                )
                MainTab.DIAGNOSTICS -> DiagnosticsScreen(
                    caps = hardwareCaps,
                    currentEncoder = config.encoderChoice,
                    onSelectEncoder = { name, mime ->
                        viewModel.selectEncoder(name, mime)
                        Toast.makeText(context, "Selected encoder: $name", Toast.LENGTH_SHORT).show()
                    },
                    onApplyRecommended = {
                        viewModel.applyRecommendedSettings()
                        viewModel.selectTab(MainTab.STUDIO)
                        Toast.makeText(context, "Recommended hardware settings applied!", Toast.LENGTH_SHORT).show()
                    }
                )
                MainTab.RECORDINGS -> RecordingsScreen(
                    recordings = recordings,
                    onRefresh = { viewModel.refreshRecordings() },
                    onDelete = { viewModel.deleteRecording(it) }
                )
            }
        }
    }
}
