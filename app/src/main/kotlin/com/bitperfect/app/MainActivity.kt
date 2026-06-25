package com.bitperfect.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val activeDevice = appViewModel.activeDevice.value
            when {
                activeDevice is OutputDevice.Upnp -> when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP   -> { appViewModel.adjustWiimVolume(+5);     return true }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { appViewModel.adjustWiimVolume(-5);     return true }
                }
                activeDevice is OutputDevice.UsbDac -> when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP   -> { appViewModel.adjustUsbDacVolume(+0.05f); return true }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { appViewModel.adjustUsbDacVolume(-0.05f); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
            val context = androidx.compose.ui.platform.LocalContext.current

            val bluetoothPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                // Open sheet regardless of grant outcome;
                // OutputRepository handles the empty-list case gracefully.
                appViewModel.openOutputDeviceSheet()
            }

            fun openOutputSheetWithPermissionCheck() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val needed = arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                    val allGranted = needed.all {
                        ContextCompat.checkSelfPermission(context, it) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) appViewModel.openOutputDeviceSheet()
                    else bluetoothPermLauncher.launch(needed)
                } else {
                    appViewModel.openOutputDeviceSheet()
                }
            }

            LaunchedEffect(Unit) {
                appViewModel.shareIntent.collect { intent ->
                    startActivity(Intent.createChooser(intent, "Share rip info"))
                }
            }

            val navController = rememberNavController()

            val isControllerReady by appViewModel.isControllerReady.collectAsState()
            val currentTrackTitle by appViewModel.currentTrackTitle.collectAsState()
            val currentTrackArtist by appViewModel.currentTrackArtist.collectAsState()
            val currentAlbumArtUri by appViewModel.currentAlbumArtUri.collectAsState()

            var dominantColor by remember { mutableStateOf(Color(0xFF141414)) }
            val contextForColor = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(currentAlbumArtUri) {
                dominantColor = Color(0xFF141414)
                if (currentAlbumArtUri != null) {
                    com.bitperfect.app.ui.utils.ColorExtractor.extractVividColor(contextForColor, currentAlbumArtUri.toString())?.let { extractedColor ->
                        dominantColor = extractedColor
                    }
                }
            }
            val fallbackColor = MaterialTheme.colorScheme.primary
            val displayColor = if (dominantColor == Color(0xFF141414)) fallbackColor else dominantColor

            val snackbarHostState = remember { SnackbarHostState() }
            val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
                bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    skipHiddenState = true
                ),
                snackbarHostState = snackbarHostState
            )

            val driveStatus by appViewModel.driveStatus.collectAsState()
            val dacState by appViewModel.dacState.collectAsState()

            val activeDevice by appViewModel.activeDevice.collectAsState()
            val availableDevices by appViewModel.availableDevices.collectAsState()
            val showOutputSheet by appViewModel.showOutputSheet.collectAsState()
            val isExternalOutput = activeDevice !is OutputDevice.ThisPhone
            val hasExternalVolume = activeDevice is OutputDevice.Upnp || activeDevice is OutputDevice.UsbDac

            LaunchedEffect(driveStatus) {
                if (driveStatus !is DriveStatus.NoDrive && driveStatus !is DriveStatus.NotOptical) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                // Prompt once when a disc is ready — before the user can start a rip.
                if (driveStatus is DriveStatus.DiscReady) {
                    promptBatteryOptimizationIfNeeded()
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
                    sheetDragHandle = null,
                    sheetContent = {
                        if (currentTrackTitle != null) {
                            BackHandler(
                                enabled = bottomSheetScaffoldState.bottomSheetState.currentValue == SheetValue.Expanded ||
                                          bottomSheetScaffoldState.bottomSheetState.targetValue == SheetValue.Expanded
                            ) {
                                coroutineScope.launch {
                                    bottomSheetScaffoldState.bottomSheetState.partialExpand()
                                }
                            }
                        }

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

                        val showScreen by remember { derivedStateOf { progressProvider() > 0.01f } }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Shared Blurred Album Art Background
                            if (currentAlbumArtUri != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentAlbumArtUri)
                                        .allowHardware(false)
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

                            if (showScreen) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer { alpha = progressProvider() }
                                ) {
                                    NowPlayingScreen(
                                        viewModel = appViewModel,
                                        enabled = isControllerReady,
                                        isExternalOutput = isExternalOutput,
                                        onOutputDeviceClick = { openOutputSheetWithPermissionCheck() },
                                        onCollapse = {
                                            coroutineScope.launch {
                                                bottomSheetScaffoldState.bottomSheetState.partialExpand()
                                            }
                                        },
                                        primaryColor = displayColor
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.DeviceList,
                        modifier = Modifier.padding(
                            top = 0.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current),
                            end = innerPadding.calculateEndPadding(layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current),
                            bottom = innerPadding.calculateBottomPadding()
                        ).fillMaxSize(),
                        enterTransition = { slideInHorizontally { width -> width } + fadeIn() },
                        exitTransition = { slideOutHorizontally { width -> -width } + fadeOut() },
                        popEnterTransition = { slideInHorizontally { width -> -width } + fadeIn() },
                        popExitTransition = { slideOutHorizontally { width -> width } + fadeOut() }
                    ) {
                        composable(AppRoutes.DeviceList) {
                            val bannerState by appViewModel.ripBannerState.collectAsStateWithLifecycle()
                            val artists by appViewModel.artists.collectAsStateWithLifecycle()
                            val totalTracks by appViewModel.totalTracks.collectAsStateWithLifecycle()

                            val totalArtists = artists.size
                            val totalAlbums = artists.sumOf { it.albums.size }

                            val albumsText = if (totalAlbums == 1) "1 Album" else "$totalAlbums Albums"
                            val artistsText = if (totalArtists == 1) "1 Artist" else "$totalArtists Artists"
                            val tracksText = if (totalTracks == 1) "1 Track" else "$totalTracks Tracks"
                            val statusText = if (totalAlbums > 0) {
                                "$albumsText • $artistsText • $tracksText"
                            } else {
                                ""
                            }

                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
                                topBar = {
                                    CenterAlignedTopAppBar(
                                        title = {
                                            Text(
                                                text = statusText,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" },
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        navigationIcon = {
                                            Surface(
                                                color = Color(0xFF191C20),
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier.padding(start = 16.dp).size(32.dp)
                                            ) {
                                                Image(
                                                    painter = painterResource(id = R.drawable.app_logo),
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null,
                                                            onClick = { navController.navigate(AppRoutes.About) }
                                                        )
                                                )
                                            }
                                        },
                                        actions = {
                                            IconButton(onClick = { navController.navigate(AppRoutes.Settings) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Settings,
                                                    contentDescription = "Settings"
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            ) { scaffoldPadding ->
                                Column(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
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
                                        },
                                        onArtistClick = { artistName ->
                                            appViewModel.selectArtist(artistName)
                                            navController.navigate(AppRoutes.Artist)
                                        }
                                    )
                                }
                            }
                        }
                        composable(AppRoutes.Settings) {
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
                                topBar = {
                                    TopAppBar(
                                        title = {
                                            Text(
                                                text = "Settings",
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        navigationIcon = {
                                            IconButton(onClick = {
                                                appViewModel.loadLibrary()
                                                navController.popBackStack()
                                            }) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Back"
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            ) { scaffoldPadding ->
                                Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
                                    SettingsScreen(
                                        driveOffsetRepository = driveOffsetRepository,
                                        settingsManager = settingsManager,
                                        viewModel = appViewModel,
                                        onCalibrateOffsetClick = {
                                            navController.navigate(AppRoutes.Calibration)
                                        }
                                    )
                                }
                            }
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
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
                                topBar = {
                                    TopAppBar(
                                        title = {
                                            Text(
                                                text = "Statistics",
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        navigationIcon = {
                                            IconButton(onClick = { navController.popBackStack() }) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "Back"
                                                )
                                            }
                                        },
                                        colors = TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.surface,
                                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            ) { scaffoldPadding ->
                                Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
                                    AboutScreen(
                                        driveOffsetRepository = driveOffsetRepository,
                                        viewModel = appViewModel,
                                        onNavigateToAlbum = { albumId, albumTitle ->
                                            appViewModel.selectAlbum(albumId, albumTitle)
                                            navController.navigate(AppRoutes.TrackList)
                                        }
                                    )
                                }
                            }
                        }
                        composable(AppRoutes.TrackList) {
                            TrackListScreen(
                                viewModel = appViewModel,
                                onShareRipInfo = { trackNumber -> appViewModel.shareRipInfo(trackNumber) },
                                onNavigateToArtist = { artistName ->
                                    appViewModel.selectArtist(artistName)
                                    navController.navigate(AppRoutes.Artist)
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable(AppRoutes.Artist) {
                            com.bitperfect.app.ui.ArtistScreen(
                                viewModel = appViewModel,
                                onNavigateToAlbum = { albumId, albumTitle ->
                                    appViewModel.selectAlbum(albumId, albumTitle)
                                    if (navController.previousBackStackEntry?.destination?.route == AppRoutes.TrackList) {
                                        navController.popBackStack() // Pop back to TrackList view
                                    } else {
                                        navController.navigate(AppRoutes.TrackList)
                                    }
                                },
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }

                if (currentTrackTitle != null) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
                    val screenHeightPx = with(density) { screenHeight.toPx() }
                    val peekHeightPx = with(density) {
                        (64.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).toPx()
                    }
                    val progressProvider: () -> Float = {
                        try {
                            val offset = bottomSheetScaffoldState.bottomSheetState.requireOffset()
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

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        AnimatedVisibility(
                            visible = showBar,
                            enter = slideInVertically { it },
                            exit = slideOutVertically { it }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { alpha = 1f - progressProvider() }
                            ) {
                            NowPlayingBar(
                                isPlayingFlow = appViewModel.isPlaying,
                                currentTrackTitle = currentTrackTitle,
                                currentTrackArtist = currentTrackArtist,
                                currentAlbumArtUri = currentAlbumArtUri,
                                enabled = isControllerReady,
                                isBitPerfect = activeDevice is OutputDevice.UsbDac,

                                isExternalOutput = isExternalOutput,
                                onPlayPause = { appViewModel.togglePlayPause() },
                                onOutputDeviceClick = { openOutputSheetWithPermissionCheck() },
                                onExpand = {
                                    coroutineScope.launch {
                                        bottomSheetScaffoldState.bottomSheetState.expand()
                                    }
                                },
                                primaryColor = displayColor
                            )
                            }
                        }
                    }
                }
            }

            if (showOutputSheet) {
                ModalBottomSheet(
                    onDismissRequest = { appViewModel.closeOutputDeviceSheet() },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = androidx.compose.ui.graphics.Color(0xFF121212),
                    dragHandle = null
                ) {
                    val isDiscovering by appViewModel.isDiscovering.collectAsState()
                    val wiimVolume by appViewModel.wiimVolume.collectAsState()
                    val usbDacVolume by appViewModel.usbDacVolume.collectAsState()
                    OutputDeviceSheet(
                        devices = availableDevices,
                        activeDevice = activeDevice,
                        dacState = dacState,
                        isDiscovering = isDiscovering,
                        wiimVolume = wiimVolume,
                        onWiimVolumeChanged = { appViewModel.setWiimVolume(it) },
                        usbDacVolume = usbDacVolume,
                        onUsbDacVolumeChanged = { appViewModel.setUsbDacVolume(it) },
                        onDeviceSelected = { appViewModel.selectOutputDevice(it) }
                    )
                }
            }
        }
    }
}
