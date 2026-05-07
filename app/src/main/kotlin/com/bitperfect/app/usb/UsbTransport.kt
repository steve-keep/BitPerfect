package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint

interface UsbTransport {
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int

    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int

    fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        var totalRead = 0
        val chunkSize = endpoint.maxPacketSize
        while (totalRead < maxLength) {
            val toRead = minOf(chunkSize, maxLength - totalRead)
            val n = bulkTransfer(endpoint, buffer, totalRead, toRead, timeout)
            if (n < 0) return if (totalRead > 0) totalRead else -1
            if (n == 0) break
            totalRead += n
        }
        return totalRead
    }
}
