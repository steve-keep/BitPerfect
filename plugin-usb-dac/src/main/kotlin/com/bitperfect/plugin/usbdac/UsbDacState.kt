package com.bitperfect.plugin.usbdac

import com.bitperfect.core.output.UacProtocol

sealed interface UsbDacState {
    data object Absent : UsbDacState
    data object PermissionPending : UsbDacState
    data class Connected(
        val device: android.hardware.usb.UsbDevice,
        val protocol: UacProtocol,
        val productName: String,
    ) : UsbDacState
}
