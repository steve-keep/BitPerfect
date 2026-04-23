package com.bitperfect.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class UsbDriveDetector(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _deviceInfo = MutableStateFlow<DriveInfo?>(null)
    val deviceInfo: StateFlow<DriveInfo?> = _deviceInfo.asStateFlow()

    private val ACTION_USB_PERMISSION = "com.bitperfect.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { Thread { interrogateDevice(it) }.start() }
                    } else {
                        Log.d(TAG, "permission denied for device $device")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { checkAndRequestPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        // For simplicity, just clearing if any device detached.
                        _deviceInfo.value = null
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        // Scan for existing devices on startup
        scanForDevices()
    }

    fun scanForDevices() {
        _deviceInfo.value = null
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (isMassStorageDevice(device)) {
                checkAndRequestPermission(device)
                break
            }
        }
    }

    fun destroy() {
        context.unregisterReceiver(usbReceiver)
    }

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // Class 8 (Mass Storage), Subclass 2 (ATAPI) or 6 (SCSI)
            if (usbInterface.interfaceClass == 8 &&
                (usbInterface.interfaceSubclass == 2 || usbInterface.interfaceSubclass == 6)) {
                return true
            }
        }
        return false
    }

    private fun checkAndRequestPermission(device: UsbDevice) {
        if (!isMassStorageDevice(device)) return

        if (usbManager.hasPermission(device)) {
            interrogateDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).apply {
                `package` = context.packageName
            }, flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun interrogateDevice(device: UsbDevice) {
        var massStorageInterface: UsbInterface? = null
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 8 && (intf.interfaceSubclass == 2 || intf.interfaceSubclass == 6)) {
                massStorageInterface = intf
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            inEndpoint = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEndpoint = ep
                        }
                    }
                }
                break
            }
        }

        if (massStorageInterface == null || inEndpoint == null || outEndpoint == null) {
            Log.e(TAG, "Could not find mass storage interface or endpoints")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Could not open connection")
            return
        }

        if (!connection.claimInterface(massStorageInterface, true)) {
            Log.e(TAG, "Could not claim interface")
            connection.close()
            return
        }

        try {
            val transport = DefaultUsbTransport(connection)
            val inquiryCommand = ScsiInquiryCommand(transport, outEndpoint, inEndpoint)

            _deviceInfo.value = inquiryCommand.execute()
        } finally {
            connection.releaseInterface(massStorageInterface)
            connection.close()
        }
    }

    companion object {
        private const val TAG = "UsbDriveDetector"
    }
}

data class DriveInfo(
    val vendorId: String,
    val productId: String,
    val isOptical: Boolean
)
