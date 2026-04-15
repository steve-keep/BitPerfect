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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bitperfect.app.ui.DeviceList
import com.bitperfect.app.ui.DiagnosticDashboard
import com.bitperfect.core.usb.UsbDeviceManager
import com.bitperfect.driver.ScsiDriver

class MainActivity : ComponentActivity() {
    private lateinit var usbDeviceManager: UsbDeviceManager
    private val scsiDriver = ScsiDriver()
    private val ACTION_USB_PERMISSION = "com.bitperfect.app.USB_PERMISSION"

    private var devices by mutableStateOf(emptyList<UsbDevice>())
    private var selectedDevice by mutableStateOf<UsbDevice?>(null)
    private var logs by mutableStateOf(listOf("App started"))
    private var inquiryData by mutableStateOf("N/A")
    private var capabilities by mutableStateOf(emptyList<String>())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { runDiagnostics(it) }
                    } else {
                        addLog("Permission denied for device $device")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbDeviceManager = UsbDeviceManager(this)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        refreshDevices()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (selectedDevice == null) {
                        DeviceList(devices = devices, onDeviceClick = { device ->
                            if (usbDeviceManager.hasPermission(device)) {
                                runDiagnostics(device)
                            } else {
                                val permissionIntent = PendingIntent.getBroadcast(
                                    this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
                                )
                                usbDeviceManager.requestPermission(device, permissionIntent)
                            }
                        })
                    } else {
                        DiagnosticDashboard(
                            inquiryData = inquiryData,
                            capabilities = capabilities,
                            logs = logs
                        )
                    }
                }
            }
        }
    }

    private fun refreshDevices() {
        devices = usbDeviceManager.getCompatibleDevices()
        addLog("Found ${devices.size} compatible devices")
    }

    private fun addLog(message: String) {
        logs = logs + message
    }

    private fun runDiagnostics(device: UsbDevice) {
        selectedDevice = device
        addLog("Running diagnostics for ${device.deviceName}")

        val connection = usbDeviceManager.openDevice(device)
        if (connection == null) {
            addLog("Failed to open device connection")
            return
        }

        val fd = connection.fileDescriptor
        addLog("Device opened, fd: $fd")

        // 1. INQUIRY
        val inquiryCmd = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        val inquiryResponse = scsiDriver.executeScsiCommand(fd, inquiryCmd, 36)
        if (inquiryResponse != null) {
            val vendor = String(inquiryResponse.sliceArray(8 until 16)).trim()
            val product = String(inquiryResponse.sliceArray(16 until 32)).trim()
            val revision = String(inquiryResponse.sliceArray(32 until 36)).trim()
            inquiryData = "$vendor $product (Rev: $revision)"
            addLog("Inquiry Success: $inquiryData")
        } else {
            addLog("Inquiry Failed")
        }

        // 2. MODE SENSE (C2 Support)
        val modeSenseCmd = byteArrayOf(0x5A, 0, 0x2A, 0, 0, 0, 0, 0, 30, 0)
        val modeSenseResponse = scsiDriver.executeScsiCommand(fd, modeSenseCmd, 30)
        if (modeSenseResponse != null) {
            val c2Support = if (modeSenseResponse[10].toInt() and 0x01 != 0) "Supported" else "Not Supported"
            capabilities = listOf("C2 Error Pointers: $c2Support")
            addLog("Mode Sense Success, C2: $c2Support")
        } else {
            addLog("Mode Sense Failed")
        }

        connection.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}
