package com.bitperfect.app.output

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

    // Stubs for future types — present in the sealed class so when-expressions stay exhaustive
    data class Upnp(
        val location: String,
        val friendlyName: String
    ) : OutputDevice() {
        override val id = location
        override val displayName = friendlyName
    }
}
