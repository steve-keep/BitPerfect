package com.bitperfect.app.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

class DefaultUsbTransport(
    private val connection: UsbDeviceConnection
) : UsbTransport {
    override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        return connection.bulkTransfer(endpoint, buffer, length, timeout)
    }

    override fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        var totalRead = 0
        val chunkSize = endpoint.maxPacketSize
        val temp = ByteArray(chunkSize)
        while (totalRead < maxLength) {
            val toRead = minOf(chunkSize, maxLength - totalRead)
            val n = connection.bulkTransfer(endpoint, temp, toRead, timeout)
            if (n < 0) return if (totalRead > 0) totalRead else -1
            System.arraycopy(temp, 0, buffer, totalRead, n)
            totalRead += n
            if (n < chunkSize) break  // short packet signals end of data
        }
        return totalRead
    }
}
