package com.bitperfect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.bitperfect.app.ui.DeviceList
import com.bitperfect.app.ui.SettingsScreen
import com.bitperfect.app.ui.theme.BitPerfectTheme
import com.bitperfect.app.usb.UsbDriveDetector
import com.bitperfect.core.utils.SettingsManager

private sealed class ScreenState {
    object DeviceList : ScreenState()
    object Settings : ScreenState()
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var usbDriveDetector: UsbDriveDetector

    private var isShowingSettings by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsManager = SettingsManager(this)
        usbDriveDetector = UsbDriveDetector(this)

        setContent {
            val driveStatus by usbDriveDetector.driveStatus.collectAsState()
            val driveInfo = (driveStatus as? com.bitperfect.app.usb.DriveStatus.DiscReady)?.info

            BitPerfectTheme {

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFF191C20),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(end = 12.dp).size(32.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.app_logo),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Text(
                                        text = when {
                                            isShowingSettings -> "Settings"
                                            else -> "BitPerfect"
                                        },
                                        modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" }
                                    )
                                }
                            },
                            navigationIcon = {
                                if (isShowingSettings) {
                                    IconButton(onClick = {
                                        isShowingSettings = false
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (!isShowingSettings) {
                                    IconButton(onClick = { isShowingSettings = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings"
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).safeDrawingPadding()) {
                            val currentState = when {
                                isShowingSettings -> ScreenState.Settings
                                else -> ScreenState.DeviceList
                            }

                            AnimatedContent(
                                targetState = currentState,
                                transitionSpec = {
                                    if (targetState is ScreenState.Settings) {
                                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> -width } + fadeOut())
                                    } else {
                                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> width } + fadeOut())
                                    }.using(SizeTransform(clip = false))
                                },
                                label = "ScreenTransition"
                            ) { state ->
                                when (state) {
                                    is ScreenState.Settings -> {
                                        SettingsScreen(
                                            settingsManager = settingsManager,
                                            driveInfo = driveInfo
                                        )
                                    }
                                    is ScreenState.DeviceList -> {
                                        DeviceList(driveInfo = driveInfo)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbDriveDetector.destroy()
    }
}
