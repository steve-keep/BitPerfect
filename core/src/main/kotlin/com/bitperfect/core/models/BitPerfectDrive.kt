package com.bitperfect.core.models

import android.hardware.usb.UsbDevice

sealed class BitPerfectDrive {
    abstract val name: String
    abstract val manufacturer: String?
    abstract val identifier: String

    data class Physical(val device: UsbDevice) : BitPerfectDrive() {
        override val name: String = device.productName ?: "Unknown Drive"
        override val manufacturer: String? = device.manufacturerName
        override val identifier: String = device.deviceName
    }

    data class Virtual(val id: Int, val vendor: String, val product: String) : BitPerfectDrive() {
        override val name: String = "$product (Simulation)"
        override val manufacturer: String = vendor
        override val identifier: String = "virtual_$id"
    }
}
