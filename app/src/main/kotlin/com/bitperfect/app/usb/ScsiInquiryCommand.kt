package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.nio.ByteBuffer

class ScsiInquiryCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(): DriveInfo? {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x43425355) // dCBWSignature
        buffer.putInt(1)          // dCBWTag
        buffer.putInt(36)         // dCBWDataTransferLength (INQUIRY needs 36 bytes)
        buffer.put(0x80.toByte()) // bmCBWFlags: 0x80 for IN
        buffer.put(0)             // bCBWLUN
        buffer.put(6)             // bCBWCBLength (INQUIRY command length)

        // SCSI INQUIRY Command Block (6 bytes)
        buffer.put(0x12)          // Opcode: INQUIRY
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(36)            // Allocation length
        buffer.put(0)

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            Log.e(TAG, "Failed to send CBW")
            return null
        }

        // Read Data
        val inquiryData = ByteArray(36)
        transferred = transport.bulkTransfer(inEndpoint, inquiryData, inquiryData.size, 5000)
        if (transferred < 0) {
            Log.e(TAG, "Failed to read INQUIRY data")
            return null
        }

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            Log.e(TAG, "Failed to read CSW")
            return null
        }

        // Parse Inquiry Data
        val peripheralDeviceType = inquiryData[0].toInt() and 0x1F
        val vendorIdBytes = inquiryData.copyOfRange(8, 16)
        val productIdBytes = inquiryData.copyOfRange(16, 32)

        val vendorId = String(vendorIdBytes, Charsets.US_ASCII).trim()
        val productId = String(productIdBytes, Charsets.US_ASCII).trim()

        // Optional: check if optical drive (type 5)
        val isOptical = peripheralDeviceType == 5

        return DriveInfo(vendorId, productId, isOptical)
    }

    companion object {
        private const val TAG = "ScsiInquiryCommand"
    }
}
