package com.bitperfect.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.bitperfect.app.ui.DeviceList
import com.bitperfect.app.ui.DiagnosticDashboard
import com.bitperfect.app.ui.SettingsScreen
import com.bitperfect.app.ui.theme.BitPerfectTheme
import android.content.ClipboardManager
import android.widget.Toast
import com.bitperfect.core.engine.*
import com.bitperfect.core.usb.UsbDeviceManager
import com.bitperfect.core.utils.SettingsManager
import com.bitperfect.driver.ScsiDriver
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import android.content.ServiceConnection
import android.os.IBinder

private sealed class ScreenState {
    object DeviceList : ScreenState()
    object Diagnostics : ScreenState()
    object Settings : ScreenState()
}

class MainActivity : ComponentActivity() {
    private lateinit var usbDeviceManager: UsbDeviceManager
    private lateinit var settingsManager: SettingsManager
    private val scsiDriver = ScsiDriver()
    private val virtualScsiDriver by lazy { VirtualScsiDriver(settingsManager.getSelectedTestCd()) }
    private var rippingService: RippingService? = null
    private var isBound = false
    private var pollingJob: kotlinx.coroutines.Job? = null
    private val ACTION_USB_PERMISSION = "com.bitperfect.app.USB_PERMISSION"

    private var devices by mutableStateOf(emptyList<BitPerfectDrive>())
    private var selectedDevice by mutableStateOf<BitPerfectDrive?>(null)
    private var isShowingSettings by mutableStateOf(false)
    private var logs by mutableStateOf(listOf("App started"))
    private var detectedCapabilities by mutableStateOf<DriveCapabilities?>(null)

    private var ripState by mutableStateOf(RipState())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as RippingService.LocalBinder
            rippingService = binder.getService()
            rippingService?.rippingEngine?.onLog = { message -> addLog(message) }
            isBound = true

            lifecycleScope.launch {
                rippingService?.rippingEngine?.ripState?.collect {
                    ripState = it
                    if (it.isRunning) {
                        stopPolling()
                    } else if (selectedDevice != null && pollingJob == null) {
                        startPolling(selectedDevice!!)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            rippingService = null
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { runDiagnostics(BitPerfectDrive.Physical(it)) }
                        } else {
                            addLog("Permission denied for device $device")
                            Toast.makeText(context, "USB permission is required to access the CD drive", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { detachedDevice ->
                        val current = selectedDevice
                        if (current is BitPerfectDrive.Physical && current.device.deviceName == detachedDevice.deviceName) {
                            rippingService?.cancelRip()
                            stopPolling()
                            selectedDevice = null
                            addLog("Drive disconnected: ${detachedDevice.deviceName}")
                            Toast.makeText(context, "Drive disconnected", Toast.LENGTH_SHORT).show()
                        }
                    }
                    refreshDevices()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.initialize(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usbDeviceManager = UsbDeviceManager(this)
        settingsManager = SettingsManager(this)

        val serviceIntent = Intent(this, RippingService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )

        refreshDevices()

        setContent {
            BitPerfectTheme {
                val windowSizeClass = calculateWindowSizeClass(this)

                val requestPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted: Boolean ->
                    if (!isGranted) {
                        Toast.makeText(this, "Notification permission is required for ripping progress", Toast.LENGTH_LONG).show()
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

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
                                            selectedDevice != null -> "Drive Diagnostics"
                                            else -> "BitPerfect"
                                        },
                                        modifier = androidx.compose.ui.Modifier.semantics { testTag = "status_label" }
                                    )
                                }
                            },
                            navigationIcon = {
                                if (isShowingSettings || selectedDevice != null) {
                                    IconButton(onClick = {
                                        if (isShowingSettings) {
                                            isShowingSettings = false
                                            refreshDevices()
                                        } else {
                                            stopPolling()
                                            selectedDevice = null
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (selectedDevice == null && !isShowingSettings) {
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
                                selectedDevice != null -> ScreenState.Diagnostics
                                else -> ScreenState.DeviceList
                            }

                            AnimatedContent(
                                targetState = currentState,
                                transitionSpec = {
                                    if (targetState is ScreenState.Settings || targetState is ScreenState.Diagnostics) {
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
                                            onCopyDebugReport = {
                                                copyDebugReportToClipboard()
                                            }
                                        )
                                    }
                                    is ScreenState.DeviceList -> {
                                        DeviceList(devices = devices, onDeviceClick = { drive ->
                                            when (drive) {
                                                is BitPerfectDrive.Physical -> {
                                                    if (usbDeviceManager.hasPermission(drive.device)) {
                                                        runDiagnostics(drive)
                                                    } else {
                                                        val intent = Intent(ACTION_USB_PERMISSION).apply {
                                                            setPackage(packageName)
                                                        }
                                                        val permissionIntent = PendingIntent.getBroadcast(
                                                            this@MainActivity, 0, Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }, PendingIntent.FLAG_MUTABLE
                                                        )
                                                        usbDeviceManager.requestPermission(drive.device, permissionIntent)
                                                    }
                                                }
                                                is BitPerfectDrive.Virtual -> {
                                                    runDiagnostics(drive)
                                                }
                                            }
                                        })
                                    }
                                    is ScreenState.Diagnostics -> {
                                        DiagnosticDashboard(
                                            driveCapabilities = detectedCapabilities,
                                            ripState = ripState,
                                            logs = logs,
                                            onStartRip = {
                                                selectedDevice?.let { startRip(it) }
                                            },
                                            onEject = {
                                                selectedDevice?.let { ejectDisc(it) }
                                            },
                                            onLoadTray = {
                                                selectedDevice?.let { loadTray(it) }
                                            },
                                            onCopyDebugReport = {
                                                copyDebugReportToClipboard()
                                            },
                                            onRetry = {
                                                selectedDevice?.let { retryPoll(it) }
                                            },
                                            onCancelRip = {
                                                rippingService?.cancelRip()
                                            },
                                            onCalibrateOffset = {
                                                selectedDevice?.let { calibrateDriveOffset(it) }
                                            },
                                            onMetadataSelect = { metadata ->
                                                rippingService?.rippingEngine?.selectMetadata(metadata)
                                            }
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

    private fun refreshDevices() {
        val physicalDevices = usbDeviceManager.getCompatibleDevices().map { BitPerfectDrive.Physical(it) }
        val virtualDevices = if (settingsManager.isVirtualDriveEnabled) {
            listOf(BitPerfectDrive.Virtual(0, "ASUS", "DRW-24B1ST   a"))
        } else {
            emptyList()
        }
        devices = physicalDevices + virtualDevices
        addLog("Found ${devices.size} compatible devices (${physicalDevices.size} USB, ${virtualDevices.size} Virtual)")
    }

    private fun addLog(message: String) {
        logs = logs + message
    }

    private data class UsbEndpoints(val endpointIn: Int, val endpointOut: Int)

    private fun getMassStorageInterface(device: UsbDevice): android.hardware.usb.UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                return iface
            }
        }
        return null
    }

    private fun getEndpoints(device: UsbDevice): UsbEndpoints {
        val iface = getMassStorageInterface(device) ?: device.getInterface(0)
        var endpointIn = 0x81
        var endpointOut = 0x01
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                endpointIn = ep.address
            } else {
                endpointOut = ep.address
            }
        }
        return UsbEndpoints(endpointIn, endpointOut)
    }

    private fun startPolling(drive: BitPerfectDrive) {
        if (pollingJob != null) return

        pollingJob = lifecycleScope.launch {
            while (true) {
                if (ripState.isRunning) {
                    stopPolling()
                    break
                }

                val driverToUse = if (drive is BitPerfectDrive.Virtual) {
                    virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
                    virtualScsiDriver
                } else {
                    scsiDriver
                }

                if (drive is BitPerfectDrive.Physical) {
                    val device = drive.device
                    val connection = usbDeviceManager.openDevice(device)
                    if (connection != null) {
                        try {
                            val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                            if (connection.claimInterface(iface, true)) {
                                try {
                                    val fd = connection.fileDescriptor
                                    val endpoints = getEndpoints(device)
                                    rippingService?.pollStatus(fd, driverToUse, endpoints.endpointIn, endpoints.endpointOut)
                                } catch (e: Exception) {
                                    addLog("Polling error: ${e.message}")
                                } finally {
                                    connection.releaseInterface(iface)
                                }
                            }
                        } finally {
                            connection.close()
                        }
                    }
                } else {
                    rippingService?.pollStatus(999, driverToUse, 0x81, 0x01)
                }

                kotlinx.coroutines.delay(2000)
            }
        }
    }


    private fun retryPoll(drive: BitPerfectDrive) {
        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver
        } else {
            scsiDriver
        }

        lifecycleScope.launch(Dispatchers.IO) {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                val connection = usbDeviceManager.openDevice(device)
                if (connection != null) {
                    try {
                        val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                        if (connection.claimInterface(iface, true)) {
                            try {
                                val fd = connection.fileDescriptor
                                val endpoints = getEndpoints(device)
                                rippingService?.pollStatus(fd, driverToUse, endpoints.endpointIn, endpoints.endpointOut, forceRefresh = true)
                            } finally {
                                connection.releaseInterface(iface)
                            }
                        }
                    } finally {
                        connection.close()
                    }
                }
            } else {
                rippingService?.pollStatus(999, driverToUse, 0x81, 0x01, forceRefresh = true)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun calibrateDriveOffset(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                val connection = usbDeviceManager.openDevice(device) ?: return@launch
                try {
                    val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                    if (connection.claimInterface(iface, true)) {
                        try {
                            val fd = connection.fileDescriptor
                            val endpoints = getEndpoints(device)
                            val caps = detectedCapabilities ?: DriveCapabilities()
                            val result = rippingService?.rippingEngine?.calibrateOffset(fd, caps, driverToUse, endpoints.endpointIn, endpoints.endpointOut)

                            if (result != null && result.isSuccess) {
                                val offset = result.getOrThrow()
                                val updatedCaps = caps.copy(readOffset = offset, offsetFromAccurateRip = false)
                                detectedCapabilities = updatedCaps
                                settingsManager.saveDriveCapabilities(drive.identifier, updatedCaps)
                                addLog("Calibration Success: Found offset $offset")
                                Toast.makeText(this@MainActivity, "Calibration successful: Offset $offset", Toast.LENGTH_LONG).show()
                            } else {
                                val errorMsg = result?.exceptionOrNull()?.message ?: "Unknown error"
                                addLog("Calibration Failed: $errorMsg")
                                Toast.makeText(this@MainActivity, "Calibration failed", Toast.LENGTH_LONG).show()
                            }
                        } finally {
                            connection.releaseInterface(iface)
                        }
                    }
                } finally {
                    connection.close()
                }
            } else {
                val caps = detectedCapabilities ?: DriveCapabilities()
                val result = rippingService?.rippingEngine?.calibrateOffset(999, caps, driverToUse, 0x81, 0x01)
                if (result != null && result.isSuccess) {
                    val offset = result.getOrThrow()
                    val updatedCaps = caps.copy(readOffset = offset, offsetFromAccurateRip = false)
                    detectedCapabilities = updatedCaps
                    settingsManager.saveDriveCapabilities(drive.identifier, updatedCaps)
                    addLog("Calibration Success: Found offset $offset")
                    Toast.makeText(this@MainActivity, "Calibration successful: Offset $offset", Toast.LENGTH_LONG).show()
                } else {
                    val errorMsg = result?.exceptionOrNull()?.message ?: "Unknown error"
                    addLog("Calibration Failed: $errorMsg")
                    Toast.makeText(this@MainActivity, "Calibration failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runDiagnostics(drive: BitPerfectDrive) {
        selectedDevice = drive
        detectedCapabilities = settingsManager.getDriveCapabilities(drive.identifier)
        startPolling(drive)
        addLog("Running diagnostics for ${drive.name}")

        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        if (drive is BitPerfectDrive.Physical) {
            val device = drive.device
            val connection = usbDeviceManager.openDevice(device)
            if (connection == null) {
                addLog("Failed to open device connection")
                return
            }

            try {
                val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                if (!connection.claimInterface(iface, true)) {
                    addLog("Failed to claim interface")
                    return
                }

                try {
                    val fd = connection.fileDescriptor
                    val endpoints = getEndpoints(device)
                    val endpointIn = endpoints.endpointIn
                    val endpointOut = endpoints.endpointOut
                    addLog("Driver: ${driverToUse.getDriverVersion()}, fd: $fd")

                    performDiagnostics(driverToUse, fd, endpointIn, endpointOut)
                } finally {
                    connection.releaseInterface(iface)
                }
            } finally {
                connection.close()
            }
        } else {
            // Virtual Drive
            addLog("Driver: ${driverToUse.getDriverVersion()}, fd: 999")
            performDiagnostics(driverToUse, 999, 0x81, 0x01)
        }
    }

    private fun performDiagnostics(driver: com.bitperfect.driver.IScsiDriver, fd: Int, endpointIn: Int, endpointOut: Int) {
        lifecycleScope.launch {
            val result = rippingService?.rippingEngine?.detectCapabilities(fd, driver, endpointIn, endpointOut)
            if (result != null && result.isSuccess) {
                val caps = result.getOrThrow()
                detectedCapabilities = caps
                addLog("Diagnostics Success: ${caps.vendor} ${caps.product} (Rev: ${caps.revision})")
                // Cache it
                selectedDevice?.let { drive ->
                    settingsManager.saveDriveCapabilities(drive.identifier, caps)
                }
            } else {
                addLog("Diagnostics Failed: ${result?.exceptionOrNull()?.message}")
            }
        }
    }

    private fun copyDebugReportToClipboard() {
        val report = DebugReportManager.generateFullReport(this, logs)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("BitPerfect Debug Report", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Debug report copied to clipboard", Toast.LENGTH_SHORT).show()
    }


    private fun ejectDisc(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                val connection = usbDeviceManager.openDevice(device) ?: return@launch
                try {
                    val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                    if (connection.claimInterface(iface, true)) {
                        try {
                            val fd = connection.fileDescriptor
                            val endpoints = getEndpoints(device)
                            rippingService?.ejectDisc(fd, driverToUse, endpoints.endpointIn, endpoints.endpointOut)
                        } finally {
                            connection.releaseInterface(iface)
                        }
                    }
                } finally {
                    connection.close()
                }
            } else {
                rippingService?.ejectDisc(999, driverToUse, 0x81, 0x01)
            }
        }
    }

    private fun loadTray(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                val connection = usbDeviceManager.openDevice(device) ?: return@launch
                try {
                    val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                    if (connection.claimInterface(iface, true)) {
                        try {
                            val fd = connection.fileDescriptor
                            val endpoints = getEndpoints(device)
                            rippingService?.loadTray(fd, driverToUse, endpoints.endpointIn, endpoints.endpointOut)
                        } finally {
                            connection.releaseInterface(iface)
                        }
                    }
                } finally {
                    connection.close()
                }
            } else {
                rippingService?.loadTray(999, driverToUse, 0x81, 0x01)
            }
        }
    }
    private fun startRip(drive: BitPerfectDrive) {
        if (ripState.isRunning) {
            addLog("Rip already in progress")
            return
        }

        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        val outputDir = settingsManager.outputFolderUri ?: getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        addLog("Starting full rip to $outputDir")

        val caps = detectedCapabilities ?: DriveCapabilities(hasCache = true)

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                addLog("Attempting to open USB device: ${device.deviceName}")
                val connection = usbDeviceManager.openDevice(device)
                if (connection == null) {
                    addLog("Failed to open USB device for ripping: usbDeviceManager.openDevice returned null (permission denied or device disconnected?)")
                    return@launch
                }
                try {
                    try {
                        val iface = device.getInterface(0)
                        if (!connection.claimInterface(iface, true)) {
                            addLog("Failed to claim interface for ripping")
                            return@launch
                        }

                        try {
                            val fd = connection.fileDescriptor
                            val (endpointIn, endpointOut) = getEndpoints(device)
                            val driveModel = "${caps.vendor} ${caps.product}".trim()
                            addLog("Successfully opened device and claimed interface 0. Starting service rip...")
                            rippingService?.startRip(fd, outputDir, driveModel, caps, driverToUse, endpointIn, endpointOut)
                        } catch (e: Exception) {
                            addLog("Exception during rip setup: ${e.message}")
                            addLog("Error details: ${android.util.Log.getStackTraceString(e)}")
                        } finally {
                            connection.releaseInterface(iface)
                        }
                    } catch (e: Exception) {
                        addLog("Exception claiming interface or opening device: ${e.message}")
                        addLog("Error details: ${android.util.Log.getStackTraceString(e)}")
                    }
                } finally {
                    connection.close()
                }
            } else {
                // Virtual Drive
                val driveModel = "${caps.vendor} ${caps.product}".trim()
                rippingService?.startRip(999, outputDir, driveModel, caps, driverToUse, 0x81, 0x01)
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
