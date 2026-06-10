package com.bitperfect.app.output

import com.bitperfect.app.usb.UacProtocol

sealed class OutputDevice {
    abstract val id: String
    abstract val displayName: String

    object ThisPhone : OutputDevice() {
        override val id = "this_phone"
        override val displayName = "This phone"
    }

    data class Bluetooth(
        val address: String,
        val name: String,
        val batteryPercent: Int? = null
    ) : OutputDevice() {
        override val id = address
        override val displayName = name
    }

    data class Upnp(
        val udn: String,
        val friendlyName: String,
        val manufacturer: String?,
        val modelName: String?,
        val deviceDescriptionUrl: String,
        val avTransportControlUrl: String?,
        val renderingControlUrl: String?,
        val ipAddress: String?
    ) : OutputDevice() {
        override val id = udn
        override val displayName = friendlyName
    }

    data class UsbDac(
        val device: android.hardware.usb.UsbDevice,
        val protocol: UacProtocol,
        val productName: String,
    ) : OutputDevice() {
        override val id = "usb_dac_${device.deviceId}"
        override val displayName = productName
    }
}
