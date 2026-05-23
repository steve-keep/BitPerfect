package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint

interface UsbTransport {
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int

    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int

    fun nextTag(): Int

    fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        var totalRead = 0
        while (totalRead < maxLength) {
            val n = bulkTransfer(endpoint, buffer, totalRead, maxLength - totalRead, timeout)
            if (n < 0) {
                return if (totalRead == 0) n else -1
            }
            if (n == 0) {
                break
            }
            totalRead += n
        }
        return totalRead
    }
}
