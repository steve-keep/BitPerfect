package com.bitperfect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context

import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.bitperfect.core.output.OutputDevice

import com.bitperfect.app.ui.OutputDeviceSheet
import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.window.DialogProperties
import android.view.WindowManager
import androidx.navigation.compose.*
import com.bitperfect.app.ui.*
import com.bitperfect.app.ui.theme.BitPerfectTheme
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.SettingsManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var driveOffsetRepository: DriveOffsetRepository
    private lateinit var settingsManager: SettingsManager

    private val appViewModel: AppViewModel by viewModels { AppViewModel.factory(application) }

    private fun promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            appViewModel.loadLibrary()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Guard against Android re-delivering the base intent when returning
            // from external activities (like the Share Sheet). If we are already
            // ripping, do not sever the connection with a rescan!
            if (!appViewModel.isRipping.value) {
                DeviceStateManager.rescan()
            }
        }
    }



    companion object {
        private var isFirstLaunch = true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        val savedStateToPass = if (isFirstLaunch) null else savedInstanceState
        super.onCreate(savedStateToPass)
        isFirstLaunch = false
        enableEdgeToEdge()

        // Request media permissions
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        driveOffsetRepository = DriveOffsetRepository(this)
        lifecycleScope.launch {
            driveOffsetRepository.initialize()
        }

        settingsManager = SettingsManager(this)


        setContent {
            MainScreen(
                appViewModel = appViewModel,
                driveOffsetRepository = driveOffsetRepository,
                settingsManager = settingsManager,
                onKeepScreenOn = { keepOn ->
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                },
                onPromptBatteryOptimization = {
                    promptBatteryOptimizationIfNeeded()
                }
            )
        }
    }
}
