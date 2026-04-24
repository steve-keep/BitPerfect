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
import com.bitperfect.core.utils.AppLogger
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class UsbDriveDetector(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _driveStatus = MutableStateFlow<DriveStatus>(DriveStatus.NoDrive)
    val driveStatus: StateFlow<DriveStatus> = _driveStatus.asStateFlow()

    private val ACTION_USB_PERMISSION = "com.bitperfect.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { Thread { interrogateDevice(it) }.start() }
                    } else {
                        AppLogger.d(TAG, "permission denied for device $device")
                        _driveStatus.value = DriveStatus.PermissionDenied
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
                        _driveStatus.value = DriveStatus.NoDrive
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
        _driveStatus.value = DriveStatus.NoDrive
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (isMassStorageDevice(device)) {
                checkAndRequestPermission(device)
                return
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
            Thread { interrogateDevice(device) }.start()
        } else {
            _driveStatus.value = DriveStatus.Connecting()
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
        _driveStatus.value = DriveStatus.Connecting()
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
            AppLogger.e(TAG, "Could not find mass storage interface or endpoints")
            _driveStatus.value = DriveStatus.Error("Could not find mass storage endpoints")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            AppLogger.e(TAG, "Could not open connection")
            _driveStatus.value = DriveStatus.Error("Could not open device")
            return
        }

        if (!connection.claimInterface(massStorageInterface, true)) {
            AppLogger.e(TAG, "Could not claim interface")
            _driveStatus.value = DriveStatus.Error("Could not open device")
            connection.close()
            return
        }

        try {
            val transport = DefaultUsbTransport(connection)
            val inquiryCommand = ScsiInquiryCommand(transport, outEndpoint, inEndpoint)

            val baseInfo = inquiryCommand.execute()
            if (baseInfo == null) {
                _driveStatus.value = DriveStatus.Error("INQUIRY command failed")
                return
            }

            if (!baseInfo.isOptical) {
                _driveStatus.value = DriveStatus.NotOptical
                return
            }

            val info = baseInfo.copy(
                usbVendorId = device.vendorId,
                usbProductId = device.productId,
                devicePath = device.deviceName
            )

            // TEST UNIT READY
            val isReady = executeTestUnitReady(transport, outEndpoint, inEndpoint)
            if (isReady) {
                _driveStatus.value = DriveStatus.DiscReady(info)
            } else {
                _driveStatus.value = DriveStatus.Empty(info)
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error interrogating device", e)
            // Try to extract existing info from state if we hit an error later in the process
            val currentInfo = _driveStatus.value.info
            _driveStatus.value = DriveStatus.Error(e.message ?: "Unknown error", currentInfo)
        } finally {
            connection.releaseInterface(massStorageInterface)
            connection.close()
        }
    }

    private fun executeTestUnitReady(transport: DefaultUsbTransport, outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint): Boolean {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x43425355) // dCBWSignature
        buffer.putInt(2)          // dCBWTag (can be anything unique)
        buffer.putInt(0)          // dCBWDataTransferLength (TUR has no data phase)
        buffer.put(0x00.toByte()) // bmCBWFlags: 0x00 for OUT / no data
        buffer.put(0)             // bCBWLUN
        buffer.put(6)             // bCBWCBLength

        // SCSI TEST UNIT READY Command Block (6 bytes)
        buffer.put(0x00)          // Opcode: TEST UNIT READY
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "TUR: Failed to send CBW")
            return false
        }

        // No Data phase for TUR

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "TUR: Failed to read CSW")
            return false
        }

        // Validate CSW
        val cswBuffer = ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val cswSignature = cswBuffer.getInt(0)
        if (cswSignature != 0x53425355) {
            AppLogger.e(TAG, "TUR: Invalid CSW signature")
            return false
        }
        val status = csw[12]
        if (status != 0.toByte()) {
            AppLogger.d(TAG, "TUR: Drive not ready (status=$status)")
            return false
        }

        return true
    }

    companion object {
        private const val TAG = "UsbDriveDetector"
    }
}

data class DriveInfo(
    val vendorId: String,
    val productId: String,
    val isOptical: Boolean,
    val usbVendorId: Int = 0,
    val usbProductId: Int = 0,
    val devicePath: String = ""
)
