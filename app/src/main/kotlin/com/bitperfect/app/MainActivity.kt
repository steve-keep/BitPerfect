package com.bitperfect.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.*
import com.bitperfect.app.ui.*
import com.bitperfect.app.ui.theme.BitPerfectTheme
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.SettingsManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var driveOffsetRepository: DriveOffsetRepository
    private lateinit var settingsManager: SettingsManager

    private val appViewModel: AppViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            appViewModel.loadLibrary()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
            val navController = rememberNavController()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route ?: AppRoutes.DeviceList

            val driveStatus by appViewModel.driveStatus.collectAsState()
            val selectedAlbumTitle by appViewModel.selectedAlbumTitle.collectAsState()

            BitPerfectTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentRoute == AppRoutes.DeviceList) {
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
                                    }
                                    Text(
                                        text = when (currentRoute) {
                                            AppRoutes.Settings -> "Settings"
                                            AppRoutes.About -> "About"
                                            AppRoutes.TrackList -> selectedAlbumTitle ?: "Album"
                                            else -> "BitPerfect"
                                        },
                                        modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" }
                                    )
                                }
                            },
                            navigationIcon = {
                                if (currentRoute != AppRoutes.DeviceList) {
                                    IconButton(onClick = {
                                        if (currentRoute == AppRoutes.TrackList || currentRoute == AppRoutes.Settings) {
                                            appViewModel.loadLibrary()
                                        }
                                        navController.popBackStack()
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (currentRoute == AppRoutes.DeviceList) {
                                    IconButton(onClick = { navController.navigate(AppRoutes.Settings) }) {
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
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.DeviceList,
                        modifier = Modifier.padding(innerPadding).fillMaxSize(),
                        enterTransition = { slideInHorizontally { width -> width } + fadeIn() },
                        exitTransition = { slideOutHorizontally { width -> -width } + fadeOut() },
                        popEnterTransition = { slideInHorizontally { width -> -width } + fadeIn() },
                        popExitTransition = { slideOutHorizontally { width -> width } + fadeOut() }
                    ) {
                        composable(AppRoutes.DeviceList) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                DeviceList(
                                    driveStatus = driveStatus,
                                    viewModel = appViewModel,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)
                                )
                                LibrarySection(
                                    viewModel = appViewModel,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    onAlbumClick = { album ->
                                        appViewModel.selectAlbum(album.id, album.title)
                                        navController.navigate(AppRoutes.TrackList)
                                    }
                                )
                            }
                        }
                        composable(AppRoutes.Settings) {
                            SettingsScreen(
                                driveOffsetRepository = driveOffsetRepository,
                                settingsManager = settingsManager,
                                viewModel = appViewModel,
                                onNavigateToAbout = {
                                    navController.navigate(AppRoutes.About)
                                }
                            )
                        }
                        composable(AppRoutes.About) {
                            AboutScreen(
                                driveOffsetRepository = driveOffsetRepository
                            )
                        }
                        composable(AppRoutes.TrackList) {
                            TrackListScreen(
                                viewModel = appViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
