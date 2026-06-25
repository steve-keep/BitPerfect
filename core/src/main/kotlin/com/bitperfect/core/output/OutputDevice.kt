package com.bitperfect.core.output

const val DEFAULT_LINKPLAY_PORT = 10095

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
        val ipAddress: String?,
        val linkPlayPort: Int = DEFAULT_LINKPLAY_PORT
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

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UsbDac) return false
            if (device.deviceId != other.device.deviceId) return false
            if (protocol != other.protocol) return false
            return true
        }

        override fun hashCode(): Int {
            var result = device.deviceId
            result = 31 * result + protocol.hashCode()
            return result
        }
    }
}
