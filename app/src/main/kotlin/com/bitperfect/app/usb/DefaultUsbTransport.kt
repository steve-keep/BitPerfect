package com.bitperfect.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.util.concurrent.atomic.AtomicInteger

class DefaultUsbTransport(
    private val connection: UsbDeviceConnection
) : UsbTransport {
    private val tagCounter = AtomicInteger(1)

    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, length, timeout)
    }

    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, offset, length, timeout)
    }

    override fun nextTag(): Int {
        return tagCounter.getAndIncrement()
    }
}
