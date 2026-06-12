package com.bitperfect.app.usb

import com.bitperfect.core.output.UacProtocol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bitperfect.core.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UsbAudioDacDetector(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow<UsbDacState>(UsbDacState.Absent)
    val state: StateFlow<UsbDacState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ACTION_USB_DAC_PERMISSION = "com.bitperfect.USB_DAC_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_DAC_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            val protocol = detectAudioClass(it)
                            if (protocol != null) {
                                _state.value = UsbDacState.Connected(
                                    device = it,
                                    protocol = protocol,
                                    productName = it.productName ?: "USB DAC"
                                )
                            }
                        }
                    } else {
                        AppLogger.w(TAG, "Permission denied for USB DAC device $device")
                        _state.value = UsbDacState.Absent
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

                    val currentState = _state.value
                    if (currentState is UsbDacState.Connected && currentState.device == device) {
                        _state.value = UsbDacState.Absent
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_USB_DAC_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        scanForDevices()
    }

    private fun scanForDevices() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            checkAndRequestPermission(device)
        }
    }

    private fun detectAudioClass(device: UsbDevice): UacProtocol? {
        var protocol: UacProtocol? = null

        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                usbInterface.interfaceSubclass == 0x02) {

                if (usbInterface.interfaceProtocol == 0x20) {
                    return UacProtocol.UAC2 // UAC2 wins, return immediately
                } else if (usbInterface.interfaceProtocol == 0x00) {
                    protocol = UacProtocol.UAC1 // UAC1 found, but keep looking for UAC2
                }
            }
        }

        return protocol
    }

    private fun checkAndRequestPermission(device: UsbDevice) {
        val protocol = detectAudioClass(device) ?: return

        if (usbManager.hasPermission(device)) {
            _state.value = UsbDacState.Connected(
                device = device,
                protocol = protocol,
                productName = device.productName ?: "USB DAC"
            )
        } else {
            _state.value = UsbDacState.PermissionPending
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_DAC_PERMISSION).apply {
                    `package` = context.packageName
                }, flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    fun destroy() {
        context.unregisterReceiver(usbReceiver)
    }

    companion object {
        private const val TAG = "UsbAudioDacDetector"
    }
}
