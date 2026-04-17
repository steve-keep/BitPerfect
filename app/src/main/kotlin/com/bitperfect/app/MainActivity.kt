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
import android.content.ServiceConnection
import android.os.IBinder

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
    private var driveCapabilities by mutableStateOf<DriveCapabilities?>(null)

    private var ripState by mutableStateOf(RipState())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as RippingService.LocalBinder
            rippingService = binder.getService()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

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
                                    Text(if (selectedDevice == null) "BitPerfect" else "Drive Diagnostics")
                                }
                            },
                            navigationIcon = {
                                if (selectedDevice != null) {
                                    IconButton(onClick = {
                                        stopPolling()
                                        selectedDevice = null
                                    }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
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
                    },
                    bottomBar = {
                        if (!isExpanded && !isShowingSettings) {
                            NavigationBar {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                    label = { Text("Home") },
                                    selected = !isShowingSettings,
                                    onClick = { isShowingSettings = false }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text("Settings") },
                                    selected = isShowingSettings,
                                    onClick = { isShowingSettings = true }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    label = { Text("About") },
                                    selected = false,
                                    onClick = { }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    if (isShowingSettings) {
                        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            SettingsScreen(
                                settingsManager = settingsManager,
                                onBack = {
                                    isShowingSettings = false
                                    refreshDevices()
                                },
                                onCopyDebugReport = {
                                    copyDebugReportToClipboard()
                                }
                            )
                        }
                    } else {
                        Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            if (isExpanded) {
                                NavigationRail(
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    NavigationRailItem(
                                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                                        label = { Text("Home") },
                                        selected = !isShowingSettings,
                                        onClick = { isShowingSettings = false }
                                    )
                                    NavigationRailItem(
                                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        label = { Text("Settings") },
                                        selected = isShowingSettings,
                                        onClick = { isShowingSettings = true }
                                    )
                                    NavigationRailItem(
                                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        label = { Text("About") },
                                        selected = false,
                                        onClick = { }
                                    )
                                }
                            }

                            Box(modifier = Modifier.weight(1f).safeDrawingPadding()) {
                                AnimatedContent(
                                    targetState = selectedDevice,
                                transitionSpec = {
                                    if (targetState != null) {
                                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> -width } + fadeOut())
                                    } else {
                                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                            slideOutHorizontally { width -> width } + fadeOut())
                                    }.using(SizeTransform(clip = false))
                                },
                                    label = "ScreenTransition"
                                ) { targetDevice ->
                                    if (targetDevice == null) {
                                        DeviceList(devices = devices, onDeviceClick = { drive ->
                                            when (drive) {
                                                is BitPerfectDrive.Physical -> {
                                                    if (usbDeviceManager.hasPermission(drive.device)) {
                                                        runDiagnostics(drive)
                                                    } else {
                                                        val permissionIntent = PendingIntent.getBroadcast(
                                                            this@MainActivity, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
                                                        )
                                                        usbDeviceManager.requestPermission(drive.device, permissionIntent)
                                                    }
                                                }
                                                is BitPerfectDrive.Virtual -> {
                                                    runDiagnostics(drive)
                                                }
                                            }
                                        })
                                    } else {

                                        DiagnosticDashboard(
                                            driveCapabilities = driveCapabilities,
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
            listOf(BitPerfectDrive.Virtual(0, "BITPERF", "VIRTUAL DRIVE"))
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

    private fun getEndpoints(device: UsbDevice): UsbEndpoints {
        val iface = device.getInterface(0)
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
                            val iface = device.getInterface(0)
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

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun runDiagnostics(drive: BitPerfectDrive) {
        selectedDevice = drive
        driveCapabilities = settingsManager.getDriveCapabilities(drive.identifier)
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
                val iface = device.getInterface(0)
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
                driveCapabilities = caps
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
                    val iface = device.getInterface(0)
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
                    val iface = device.getInterface(0)
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

        val caps = driveCapabilities ?: DriveCapabilities(hasCache = true)

        lifecycleScope.launch {
            if (drive is BitPerfectDrive.Physical) {
                val device = drive.device
                val connection = usbDeviceManager.openDevice(device) ?: return@launch
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
                        rippingService?.startRip(fd, outputDir, driveModel, caps, driverToUse, endpointIn, endpointOut)
                    } finally {
                        connection.releaseInterface(iface)
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
