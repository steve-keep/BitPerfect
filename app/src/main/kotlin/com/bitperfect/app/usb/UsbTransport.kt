package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint

interface UsbTransport {
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int

    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int

    fun nextTag(): Int

    fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        val n = bulkTransfer(endpoint, buffer, 0, maxLength, timeout)
        if (n == maxLength || n < 0) return n

        var totalRead = n
        while (totalRead < maxLength) {
            val toRead = minOf(endpoint.maxPacketSize, maxLength - totalRead)
            val m = bulkTransfer(endpoint, buffer, totalRead, toRead, timeout)
            if (m <= 0) break
            totalRead += m
        }
        return totalRead
    }
}
