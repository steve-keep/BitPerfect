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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class UsbDriveDetector(
    private val context: Context,
    private val transportFactory: ((android.hardware.usb.UsbDeviceConnection) -> UsbTransport)? = null
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _driveStatus = MutableStateFlow<DriveStatus>(DriveStatus.NoDrive)
    val driveStatus: StateFlow<DriveStatus> = _driveStatus.asStateFlow()

    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var massStorageInterface: UsbInterface? = null
    private var transport: UsbTransport? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val ACTION_USB_PERMISSION = "com.bitperfect.app.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { Thread { interrogateDevice(it) }.start() }
                    } else {
                        AppLogger.d(TAG, "permission denied for device $device")
                        _driveStatus.value = DriveStatus.PermissionDenied
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { checkAndRequestPermission(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        // For simplicity, just clearing if any device detached.
                        _driveStatus.value = DriveStatus.NoDrive
                        pollingJob?.cancel()
                        cleanupConnection()
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

    fun reportError(message: String) {
        val currentInfo = _driveStatus.value.info
        _driveStatus.value = DriveStatus.Error(message, currentInfo)
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
        pollingJob?.cancel()
        cleanupConnection()
    }

    private fun cleanupConnection() {
        try {
            massStorageInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cleaning up USB connection", e)
        } finally {
            usbConnection = null
            massStorageInterface = null
            transport = null
            inEndpoint = null
            outEndpoint = null
        }
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

        // Keep local references that will be assigned to class properties
        val transportLocal = transportFactory?.invoke(connection) ?: DefaultUsbTransport(connection)

        this.usbConnection = connection
        this.massStorageInterface = massStorageInterface
        this.transport = transportLocal
        this.inEndpoint = inEndpoint
        this.outEndpoint = outEndpoint

        try {
            val inquiryCommand = ScsiInquiryCommand(transportLocal, outEndpoint, inEndpoint)

            val baseInfo = inquiryCommand.execute()
            if (baseInfo == null) {
                _driveStatus.value = DriveStatus.Error("INQUIRY command failed")
                cleanupConnection()
                return
            }

            if (!baseInfo.isOptical) {
                _driveStatus.value = DriveStatus.NotOptical
                cleanupConnection()
                return
            }

            val info = baseInfo.copy(
                usbVendorId = device.vendorId,
                usbProductId = device.productId,
                devicePath = device.deviceName
            )

            // TEST UNIT READY
            val isReady = executeTestUnitReady(transportLocal, outEndpoint, inEndpoint)
            if (isReady) {
                val toc = readTocWithRetry(transportLocal, outEndpoint, inEndpoint)
                _driveStatus.value = DriveStatus.DiscReady(info, toc)
            } else {
                _driveStatus.value = DriveStatus.Empty(info)
            }

            // Start polling loop which takes ownership of cleaning up the connection
            startPollingLoop(info)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error interrogating device", e)
            val currentInfo = _driveStatus.value.info
            _driveStatus.value = DriveStatus.Error(e.message ?: "Unknown error", currentInfo)
            cleanupConnection()
        }
    }

    private fun startPollingLoop(info: DriveInfo) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            try {
                var cbwTag = 100 // Start from a reasonably high tag to avoid overlap with interrogate
                while (isActive) {
                    delay(2000)

                    val currentTransport = transport
                    val currentOutEndpoint = outEndpoint
                    val currentInEndpoint = inEndpoint

                    if (currentTransport == null || currentOutEndpoint == null || currentInEndpoint == null) {
                        break
                    }

                    val isReady = executeSingleTestUnitReady(currentTransport, currentOutEndpoint, currentInEndpoint, cbwTag)
                    cbwTag += 2

                    val currentStatus = _driveStatus.value
                    if (isReady && currentStatus is DriveStatus.Empty) {
                        val toc = readTocWithRetry(currentTransport, currentOutEndpoint, currentInEndpoint, cbwTag + 50)
                        _driveStatus.value = DriveStatus.DiscReady(info, toc)
                    } else if (!isReady && currentStatus is DriveStatus.DiscReady) {
                        _driveStatus.value = DriveStatus.Empty(info)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in polling loop", e)
            } finally {
                cleanupConnection()
            }
        }
    }

    private fun executeTestUnitReady(transport: UsbTransport, outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint): Boolean {
        for (attempt in 1..10) {
            if (executeSingleTestUnitReady(transport, outEndpoint, inEndpoint, attempt * 2)) {
                return true
            }
            if (attempt < 10) {
                Thread.sleep(1000)
            }
        }
        AppLogger.w(TAG, "TUR: Exhausted all attempts, drive not ready")
        return false
    }

    private fun executeSingleTestUnitReady(transport: UsbTransport, outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint, tag: Int): Boolean {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x43425355) // dCBWSignature
        buffer.putInt(tag)        // dCBWTag (can be anything unique)
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
            AppLogger.e(TAG, "TUR: Failed to send CBW on tag $tag")
            return false
        }

        // No Data phase for TUR

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "TUR: Failed to read CSW on tag $tag")
            return false
        }

        // Validate CSW
        val cswBuffer = ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val cswSignature = cswBuffer.getInt(0)
        if (cswSignature != 0x53425355) {
            AppLogger.e(TAG, "TUR: Invalid CSW signature on tag $tag")
            return false
        }
        val status = csw[12]
        if (status != 0.toByte()) {
            AppLogger.d(TAG, "TUR: Drive not ready (status=$status) on tag $tag")
            executeRequestSense(transport, outEndpoint, inEndpoint, tag + 1)
            return false
        }

        return true
    }

    private fun readTocWithRetry(transport: UsbTransport, outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint, tagOffset: Int = 50): com.bitperfect.core.models.DiscToc? {
        val tocCommand = ReadTocCommand(transport, outEndpoint, inEndpoint)
        var toc: com.bitperfect.core.models.DiscToc? = null
        for (attempt in 1..3) {
            toc = tocCommand.execute(tagOffset + attempt - 1)
            if (toc != null) break
            if (attempt < 3) {
                Thread.sleep(500)
            }
        }
        if (toc == null) {
            AppLogger.w(TAG, "TOC is null after DiscReady")
        }
        return toc
    }

    private fun executeRequestSense(transport: UsbTransport, outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint, tag: Int) {
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x43425355) // dCBWSignature
        buffer.putInt(tag)        // dCBWTag
        buffer.putInt(18)         // dCBWDataTransferLength (18 bytes for Request Sense)
        buffer.put(0x80.toByte()) // bmCBWFlags: 0x80 for IN
        buffer.put(0)             // bCBWLUN
        buffer.put(6)             // bCBWCBLength

        // SCSI REQUEST SENSE Command Block (6 bytes)
        buffer.put(0x03)          // Opcode: REQUEST SENSE
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(18)            // Allocation length
        buffer.put(0)

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 3000)
        if (transferred < 0) return

        // Read Data
        val senseData = ByteArray(18)
        transferred = transport.bulkTransfer(inEndpoint, senseData, senseData.size, 3000)
        if (transferred < 0) return

        // Read CSW
        val csw = ByteArray(13)
        transport.bulkTransfer(inEndpoint, csw, csw.size, 3000)
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
