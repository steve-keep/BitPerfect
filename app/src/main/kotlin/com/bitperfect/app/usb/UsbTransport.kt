package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint

interface UsbTransport {
    fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int

    fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
        return bulkTransfer(endpoint, buffer, maxLength, timeout)
    }
}
