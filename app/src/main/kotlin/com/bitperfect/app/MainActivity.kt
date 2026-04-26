package com.bitperfect.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.bitperfect.app.ui.AboutScreen
import com.bitperfect.app.ui.DeviceList
import com.bitperfect.app.ui.HomeViewModel
import com.bitperfect.app.ui.LibrarySection
import com.bitperfect.app.ui.SettingsScreen
import com.bitperfect.app.ui.theme.BitPerfectTheme
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.core.utils.SettingsManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.bitperfect.core.services.DriveOffsetRepository
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat


private sealed class ScreenState {
    object DeviceList : ScreenState()
    object Settings : ScreenState()
    object About : ScreenState()
}


class MainActivity : ComponentActivity() {
    private lateinit var driveOffsetRepository: DriveOffsetRepository

    private lateinit var settingsManager: SettingsManager

    private val homeViewModel: HomeViewModel by viewModels()

    private var currentScreen by mutableStateOf<ScreenState>(ScreenState.DeviceList)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            homeViewModel.loadLibrary()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        driveOffsetRepository = DriveOffsetRepository(this)
        lifecycleScope.launch {
            driveOffsetRepository.initialize()
        }

        settingsManager = SettingsManager(this)

        setContent {
            val driveStatus by DeviceStateManager.driveStatus.collectAsState()

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
                                        text = when (currentScreen) {
                                            is ScreenState.Settings -> "Settings"
                                            is ScreenState.About -> "About"
                                            else -> "BitPerfect"
                                        },
                                        modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" }
                                    )
                                }
                            },
                            navigationIcon = {
                                if (currentScreen != ScreenState.DeviceList) {
                                    IconButton(onClick = {
                                        val nextScreen = when (currentScreen) {
                                            is ScreenState.About -> ScreenState.Settings
                                            is ScreenState.Settings -> ScreenState.DeviceList
                                            else -> ScreenState.DeviceList
                                        }
                                        if (nextScreen == ScreenState.DeviceList) {
                                            homeViewModel.loadLibrary()
                                        }
                                        currentScreen = nextScreen
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == ScreenState.DeviceList) {
                                    IconButton(onClick = { currentScreen = ScreenState.Settings }) {
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
                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = {
                                    fun getIndex(state: ScreenState) = when (state) {
                                        ScreenState.DeviceList -> 0
                                        ScreenState.Settings -> 1
                                        ScreenState.About -> 2
                                    }
                                    val targetIndex = getIndex(targetState)
                                    val initialIndex = getIndex(initialState)

                                    if (targetIndex > initialIndex) {
                                        // Sliding forward (right to left)
                                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> -width } + fadeOut())
                                    } else {
                                        // Sliding backward (left to right)
                                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> width } + fadeOut())
                                    }.using(SizeTransform(clip = false))
                                },
                                label = "ScreenTransition"
                            ) { state ->
                                when (state) {
                                    is ScreenState.Settings -> {
                                        SettingsScreen(
                                            driveOffsetRepository = driveOffsetRepository,
                                            settingsManager = settingsManager,
                                            onNavigateToAbout = {
                                                currentScreen = ScreenState.About
                                            }
                                        )
                                    }
                                    is ScreenState.DeviceList -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            DeviceList(
                                                driveStatus = driveStatus,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                                            )
                                            LibrarySection(
                                                viewModel = homeViewModel,
                                                modifier = Modifier.fillMaxWidth().weight(1f)
                                            )
                                        }
                                    }
                                    is ScreenState.About -> {
                                        AboutScreen(
                                            driveOffsetRepository = driveOffsetRepository
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
