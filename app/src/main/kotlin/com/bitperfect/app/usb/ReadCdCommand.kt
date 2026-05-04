package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import com.bitperfect.core.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadCdCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(lba: Int, sectorCount: Int = 1, tag: Int = 1000): ByteArray? {
        val transferLength = sectorCount * 2352

        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE) // dCBWSignature
        buffer.putInt(tag)           // dCBWTag
        buffer.putInt(transferLength) // dCBWDataTransferLength
        buffer.put(0x80.toByte())    // bmCBWFlags: 0x80 for IN
        buffer.put(0)                // bCBWLUN
        buffer.put(12)               // bCBWCBLength (READ CD command length)

        // SCSI READ CD Command Block (12 bytes)
        buffer.put(0xBE.toByte())    // Opcode: READ CD
        buffer.put(0)                // Expected sector type (0=any)

        // Starting LBA (big-endian)
        buffer.put((lba shr 24).toByte())
        buffer.put((lba shr 16).toByte())
        buffer.put((lba shr 8).toByte())
        buffer.put(lba.toByte())

        // Transfer length in sectors (big-endian)
        buffer.put((sectorCount shr 16).toByte())
        buffer.put((sectorCount shr 8).toByte())
        buffer.put(sectorCount.toByte())

        // Sync=1, all headers, user data, EDC/ECC, no C2 (0xF8)
        buffer.put(0xF8.toByte())
        buffer.put(0)                // Subchannel: 0x00=none
        buffer.put(0)                // Reserved

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to send CBW for READ CD")
            return null
        }

        // Read Audio Data
        val audioData = ByteArray(transferLength)
        val totalRead = transport.bulkTransferFully(inEndpoint, audioData, transferLength, 5000)
        if (totalRead < transferLength) {
            AppLogger.e(TAG, "Failed to read full audio data: requested $transferLength, got $totalRead")
            return null
        }

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW for READ CD")
            return null
        }

        // Validate CSW
        val cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val cswSignature = cswBuffer.getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            AppLogger.e(TAG, "Invalid CSW signature for READ CD")
            return null
        }
        val status = csw[12]
        if (status != 0.toByte()) {
            AppLogger.e(TAG, "CSW indicates command failure: status=$status")
            return null
        }

        return audioData
    }

    companion object {
        private const val TAG = "ReadCdCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
