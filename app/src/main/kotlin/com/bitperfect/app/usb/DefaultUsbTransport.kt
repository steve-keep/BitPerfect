package com.bitperfect.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

class DefaultUsbTransport(
    private val connection: UsbDeviceConnection
) : UsbTransport {
    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, length, timeout)
    }
}
