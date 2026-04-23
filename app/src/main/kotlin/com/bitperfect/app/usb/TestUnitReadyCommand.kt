package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.nio.ByteBuffer

class TestUnitReadyCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(): Boolean {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE) // dCBWSignature
        buffer.putInt(2)          // dCBWTag
        buffer.putInt(0)          // dCBWDataTransferLength (TEST UNIT READY transfers no data)
        buffer.put(0)             // bmCBWFlags: 0x00 for OUT / No Data
        buffer.put(0)             // bCBWLUN
        buffer.put(6)             // bCBWCBLength (TEST UNIT READY command length)

        // SCSI TEST UNIT READY Command Block (6 bytes)
        buffer.put(0x00)          // Opcode: TEST UNIT READY
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)
        buffer.put(0)

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            Log.e(TAG, "Failed to send CBW for TEST UNIT READY")
            return false
        }

        // TEST UNIT READY has no data phase, read CSW immediately

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            Log.e(TAG, "Failed to read CSW for TEST UNIT READY")
            return false
        }

        // Validate CSW
        val cswSignature = ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            Log.e(TAG, "Invalid CSW signature")
            return false
        }

        // csw[12] is the bCSWStatus. 0 means success (ready), other values (like 1 for Check Condition) mean not ready.
        if (csw[12] != 0.toByte()) {
            Log.d(TAG, "TEST UNIT READY indicates not ready: status=${csw[12]}")
            return false
        }

        return true
    }

    companion object {
        private const val TAG = "TestUnitReadyCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
