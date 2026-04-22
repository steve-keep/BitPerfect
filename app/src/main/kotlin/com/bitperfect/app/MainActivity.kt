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
                            addLog("USB permission GRANTED for device: ${device?.deviceName}")
                            device?.let { runDiagnostics(BitPerfectDrive.Physical(it)) }
                        } else {
                            addLog("USB permission DENIED for device $device")
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
        usbDeviceManager = UsbDeviceManager(this) { logMsg -> addLog(logMsg) }
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
                                            onShareDebugReport = {
                                                shareDebugReport()
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
        addLog("Finding Mass Storage Interface for ${device.deviceName}...")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            addLog("  Interface $i: Class=${iface.interfaceClass}, Subclass=${iface.interfaceSubclass}, Protocol=${iface.interfaceProtocol}")
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                addLog("  -> Found Mass Storage Interface at index $i")
                return iface
            }
        }
        addLog("  -> Warning: No explicit Mass Storage interface found.")
        return null
    }

    private fun getEndpoints(device: UsbDevice): UsbEndpoints {
        val iface = getMassStorageInterface(device) ?: device.getInterface(0)
        var endpointIn = 0x81
        var endpointOut = 0x01
        addLog("Scanning endpoints for Interface ${iface.id} (count: ${iface.endpointCount})...")
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            addLog("  Endpoint $i: address=0x${ep.address.toString(16)}, type=${ep.type}, direction=${if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"}")
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    endpointIn = ep.address
                    addLog("  -> Selected IN Endpoint: 0x${endpointIn.toString(16)}")
                } else {
                    endpointOut = ep.address
                    addLog("  -> Selected OUT Endpoint: 0x${endpointOut.toString(16)}")
                }
            }
        }
        return UsbEndpoints(endpointIn, endpointOut)
    }

    private suspend fun withUsbDevice(
        drive: BitPerfectDrive,
        action: suspend (fd: Int, driver: com.bitperfect.driver.IScsiDriver, endpointIn: Int, endpointOut: Int) -> Unit
    ) {
        val driverToUse = if (drive is BitPerfectDrive.Virtual) {
            virtualScsiDriver.testCd = settingsManager.getSelectedTestCd()
            virtualScsiDriver
        } else {
            scsiDriver
        }

        if (drive is BitPerfectDrive.Physical) {
            val device = drive.device
            addLog("Requesting openDevice for ${drive.name}...")
            val connection = usbDeviceManager.openDevice(device)
            if (connection == null) {
                addLog("Failed to open device connection for ${drive.name}. Has permission? ${usbDeviceManager.hasPermission(device)}")
                return
            }
            addLog("Device opened successfully: ${device.deviceName}")
            try {
                val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                if (!connection.claimInterface(iface, true)) {
                    addLog("Failed to claim interface for ${drive.name}")
                    return
                }
                try {
                    val fd = connection.fileDescriptor
                    val endpoints = getEndpoints(device)
                    driverToUse.initDevice(fd, iface.id, endpoints.endpointIn, endpoints.endpointOut)
                    action(fd, driverToUse, endpoints.endpointIn, endpoints.endpointOut)
                } catch (e: Exception) {
                    addLog("Error during USB operation: ${e.message}")
                    addLog("Stack trace: ${android.util.Log.getStackTraceString(e)}")
                } finally {
                    connection.releaseInterface(iface)
                }
            } finally {
                connection.close()
            }
        } else {
            action(999, driverToUse, 0x81, 0x01)
        }
    }

    private fun startPolling(drive: BitPerfectDrive) {
        if (pollingJob != null) return

        pollingJob = lifecycleScope.launch {
            while (true) {
                if (ripState.isRunning) {
                    stopPolling()
                    break
                }
                withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                    rippingService?.pollStatus(fd, driverToUse, epIn, epOut)
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun retryPoll(drive: BitPerfectDrive) {
        lifecycleScope.launch(Dispatchers.IO) {
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                rippingService?.pollStatus(fd, driverToUse, epIn, epOut, forceRefresh = true)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun calibrateDriveOffset(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        lifecycleScope.launch {
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                val caps = detectedCapabilities ?: DriveCapabilities()
                val result = rippingService?.rippingEngine?.calibrateOffset(fd, caps, driverToUse, epIn, epOut)

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

        lifecycleScope.launch {
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                if (drive is BitPerfectDrive.Physical) {
                    val device = drive.device
                    val iface = getMassStorageInterface(device) ?: device.getInterface(0)
                    addLog("Driver: ${driverToUse.getDriverVersion()}, fd: $fd")
                    addLog("Device VID: ${device.vendorId}, PID: ${device.productId}")
                    addLog("Interface Class: ${iface.interfaceClass}, Subclass: ${iface.interfaceSubclass}, Protocol: ${iface.interfaceProtocol}")
                    addLog("Endpoint IN: $epIn, Endpoint OUT: $epOut")
                } else {
                    addLog("Driver: ${driverToUse.getDriverVersion()}, fd: 999")
                }
                performDiagnostics(driverToUse, fd, epIn, epOut)
            }
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
                result?.exceptionOrNull()?.let { ex ->
                    addLog("Diagnostics Error Details: ${ex.stackTraceToString()}")
                }

                // Fallback using USB descriptor strings if INQUIRY completely fails
                if (result?.isFailure == true || result == null) {
                    val currentDevice = selectedDevice
                    if (currentDevice is BitPerfectDrive.Physical) {
                        addLog("Using fallback capabilities from USB descriptors for ${currentDevice.name}")
                        val fallbackCaps = DriveCapabilities(
                            vendor = currentDevice.device.manufacturerName ?: "Unknown",
                            product = currentDevice.device.productName ?: "Drive",
                            revision = "Fallback"
                        )
                        detectedCapabilities = fallbackCaps
                        settingsManager.saveDriveCapabilities(currentDevice.identifier, fallbackCaps)
                    }
                }
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

    private fun shareDebugReport() {
        val report = DebugReportManager.generateFullReport(this, logs)
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, report)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Export Debug Log")
        startActivity(shareIntent)
    }


    private fun ejectDisc(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        lifecycleScope.launch {
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                rippingService?.ejectDisc(fd, driverToUse, epIn, epOut)
            }
        }
    }

    private fun loadTray(drive: BitPerfectDrive) {
        if (ripState.isRunning) return

        lifecycleScope.launch {
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                rippingService?.loadTray(fd, driverToUse, epIn, epOut)
            }
        }
    }

    private fun startRip(drive: BitPerfectDrive) {
        if (ripState.isRunning) {
            addLog("Rip already in progress")
            return
        }

        val outputDir = settingsManager.outputFolderUri ?: getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        addLog("Starting full rip to $outputDir")

        val caps = detectedCapabilities ?: DriveCapabilities(hasCache = true)

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                 addLog("Attempting to open USB device: ${drive.device.deviceName}")
            }
            withUsbDevice(drive) { fd, driverToUse, epIn, epOut ->
                 val driveModel = "${caps.vendor} ${caps.product}".trim()
                 if (drive is BitPerfectDrive.Physical) {
                     addLog("Successfully opened device and claimed interface 0. Starting service rip...")
                 }
                 rippingService?.startRip(fd, outputDir, driveModel, caps, driverToUse, epIn, epOut)
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
