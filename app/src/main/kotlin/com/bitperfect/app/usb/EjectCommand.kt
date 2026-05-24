package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import com.bitperfect.core.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EjectCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(): Boolean {
        val tag = transport.nextTag()
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE) // dCBWSignature
        buffer.putInt(tag)           // dCBWTag
        buffer.putInt(0)             // dCBWDataTransferLength (0 for START STOP UNIT)
        buffer.put(0x00.toByte())    // bmCBWFlags: 0x00 for OUT (or NONE)
        buffer.put(0)                // bCBWLUN
        buffer.put(6)                // bCBWCBLength (6 bytes for START STOP UNIT)

        // SCSI START STOP UNIT Command Block (6 bytes)
        buffer.put(0x1B.toByte())    // Opcode: START STOP UNIT
        buffer.put(0)                // Immed = 0
        buffer.put(0)                // Reserved
        buffer.put(0)                // Reserved
        buffer.put(0x02.toByte())    // LoEj = 1 (Eject), Start = 0 (Stop)
        buffer.put(0)                // Control

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to send CBW for EJECT")
            return false
        }

        // START STOP UNIT has no Data phase, so we skip directly to CSW

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW for EJECT")
            return false
        }

        // Validate CSW
        val cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val cswSignature = cswBuffer.getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            AppLogger.e(TAG, "Invalid CSW signature for EJECT")
            return false
        }
        val status = csw[12]
        if (status != 0.toByte()) {
            AppLogger.e(TAG, "CSW indicates command failure: status=$status")
            return false
        }

        return true
    }

    companion object {
        private const val TAG = "EjectCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
