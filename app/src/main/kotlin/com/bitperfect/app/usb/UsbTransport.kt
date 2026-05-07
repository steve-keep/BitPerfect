package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint

interface UsbTransport {
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int

    fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        var totalRead = 0
        val packetSize = endpoint.maxPacketSize
        val chunkSize = if (packetSize > 0) packetSize else 512
        val temp = ByteArray(chunkSize)

        while (totalRead < maxLength) {
            val toRead = minOf(chunkSize, maxLength - totalRead)
            val n = bulkTransfer(endpoint, temp, toRead, timeout)

            if (n < 0) {
                return if (totalRead > 0) totalRead else -1
            }

            if (n == 0) {
                // Zero-Length Packet (ZLP) signals end of transfer
                break
            }

            System.arraycopy(temp, 0, buffer, totalRead, n)
            totalRead += n
        }

        return totalRead
    }
}
