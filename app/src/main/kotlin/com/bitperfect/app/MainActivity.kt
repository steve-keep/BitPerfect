package com.bitperfect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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

    private val appViewModel: AppViewModel by viewModels()

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
            DeviceStateManager.rescan()
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
            LaunchedEffect(Unit) {
                appViewModel.shareIntent.collect { intent ->
                    startActivity(Intent.createChooser(intent, "Share rip info"))
                }
            }

            val navController = rememberNavController()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route ?: AppRoutes.DeviceList

            val selectedAlbumTitle by appViewModel.selectedAlbumTitle.collectAsState()
            val trackListViewState by appViewModel.trackListViewState.collectAsState()

            val isControllerReady by appViewModel.isControllerReady.collectAsState()
            val isPlaying by appViewModel.isPlaying.collectAsState()
            val currentTrackTitle by appViewModel.currentTrackTitle.collectAsState()
            val currentTrackArtist by appViewModel.currentTrackArtist.collectAsState()
            val currentAlbumArtUri by appViewModel.currentAlbumArtUri.collectAsState()

            val snackbarHostState = remember { SnackbarHostState() }
            val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
                bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    skipHiddenState = true
                ),
                snackbarHostState = snackbarHostState
            )

            val driveStatus by appViewModel.driveStatus.collectAsState()

            LaunchedEffect(driveStatus) {
                if (driveStatus !is DriveStatus.NoDrive && driveStatus !is DriveStatus.NotOptical) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(Unit) {
                appViewModel.uiEvent.collect { message ->
                    snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                }
            }

            val coroutineScope = rememberCoroutineScope()

            BitPerfectTheme {
                BottomSheetScaffold(
                    scaffoldState = bottomSheetScaffoldState,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    sheetPeekHeight = if (currentTrackTitle != null) {
                        64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    } else {
                        0.dp
                    },
                    sheetDragHandle = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.dp)
                                .padding(end = 80.dp) // Leave play/pause button area non-draggable
                        )
                    },
                    sheetContent = {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                        val screenHeightPx = with(density) { screenHeight.toPx() }
                        val peekHeightPx = with(density) {
                            if (currentTrackTitle != null) {
                                (64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).toPx()
                            } else {
                                0f
                            }
                        }

                        val progressProvider: () -> Float = {
                            try {
                                val offset = bottomSheetScaffoldState.bottomSheetState.requireOffset()
                                // offset is y coordinate. When expanded, offset is 0.
                                // When partial, offset is screenHeightPx - peekHeightPx.
                                val maxOffset = screenHeightPx - peekHeightPx
                                if (maxOffset <= 0) 0f else {
                                    val fraction = 1f - (offset / maxOffset)
                                    fraction.coerceIn(0f, 1f)
                                }
                            } catch (e: IllegalStateException) {
                                if (bottomSheetScaffoldState.bottomSheetState.targetValue == SheetValue.Expanded) 1f else 0f
                            }
                        }

                        val showBar by remember { derivedStateOf { progressProvider() < 0.99f } }
                        val showScreen by remember { derivedStateOf { progressProvider() > 0.01f } }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Shared Blurred Album Art Background
                            if (currentAlbumArtUri != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentAlbumArtUri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .blur(50.dp)
                                )
                                // Dark overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.7f))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF191C20))
                                )
                            }

                            if (showBar) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = 1f - progressProvider() }
                                ) {
                                    NowPlayingBar(
                                        isPlaying = isPlaying,
                                        currentTrackTitle = currentTrackTitle,
                                        currentTrackArtist = currentTrackArtist,
                                        currentAlbumArtUri = currentAlbumArtUri,
                                        onPlayPause = { appViewModel.togglePlayPause() },
                                        enabled = isControllerReady,
                                        onExpand = {
                                            coroutineScope.launch {
                                                bottomSheetScaffoldState.bottomSheetState.expand()
                                            }
                                        }
                                    )
                                }
                            }
                            if (showScreen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = progressProvider() }
                                ) {
                                    NowPlayingScreen(
                                        viewModel = appViewModel,
                                        onCollapse = {
                                            coroutineScope.launch {
                                                bottomSheetScaffoldState.bottomSheetState.partialExpand()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
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
                                            AppRoutes.TrackList -> trackListViewState?.title ?: selectedAlbumTitle ?: "Album"
                                            else -> "BitPerfect"
                                        },
                                        modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" },
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                    val bottomPadding = if (currentTrackTitle != null) 64.dp else 0.dp
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.DeviceList,
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding(),
                            start = innerPadding.calculateStartPadding(layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current),
                            end = innerPadding.calculateEndPadding(layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current),
                            bottom = bottomPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        ).fillMaxSize(),
                        enterTransition = { slideInHorizontally { width -> width } + fadeIn() },
                        exitTransition = { slideOutHorizontally { width -> -width } + fadeOut() },
                        popEnterTransition = { slideInHorizontally { width -> -width } + fadeIn() },
                        popExitTransition = { slideOutHorizontally { width -> width } + fadeOut() }
                    ) {
                        composable(AppRoutes.DeviceList) {
                            val bannerState by appViewModel.ripBannerState.collectAsStateWithLifecycle()
                            Column(modifier = Modifier.fillMaxSize()) {
                                DeviceList(
                                    viewModel = appViewModel,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                                    bannerState = bannerState,
                                    onNavigateToTrackList = {
                                        navController.navigate(AppRoutes.TrackList)
                                    },
                                    onViewCd = {
                                        navController.navigate(AppRoutes.TrackList)
                                    }
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
                                },
                                onCalibrateOffsetClick = {
                                    navController.navigate(AppRoutes.Calibration)
                                }
                            )
                        }
                        dialog(
                            route = AppRoutes.Calibration,
                            dialogProperties = DialogProperties(
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false
                            )
                        ) {
                            com.bitperfect.app.ui.calibration.OffsetCalibrationScreen(
                                driveOffsetRepository = driveOffsetRepository,
                                onNavigateBack = {
                                    navController.popBackStack()
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
                                viewModel = appViewModel,
                                onShareRipInfo = { trackNumber -> appViewModel.shareRipInfo(trackNumber) },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
