package com.bitperfect.app.usb

sealed interface UsbDacState {
    data object Absent : UsbDacState
    data object PermissionPending : UsbDacState
    data class Connected(
        val device: android.hardware.usb.UsbDevice,
        val protocol: UacProtocol,
        val productName: String,
    ) : UsbDacState
}

enum class UacProtocol { UAC1, UAC2 }
