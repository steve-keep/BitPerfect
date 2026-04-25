package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TrackInfo
import com.bitperfect.core.utils.AppLogger
import java.nio.ByteBuffer

class ReadTocCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(): DiscToc? {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE) // dCBWSignature
        buffer.putInt(3)          // dCBWTag (can be anything unique)
        buffer.putInt(804)        // dCBWDataTransferLength (TOC length allocation 0x0324)
        buffer.put(0x80.toByte()) // bmCBWFlags: 0x80 for IN
        buffer.put(0)             // bCBWLUN
        buffer.put(10)            // bCBWCBLength (READ TOC command length)

        // SCSI READ TOC Command Block (10 bytes)
        // Set CDB bytes directly since the CBW payload needs little-endian but CDB requires big-endian byte-by-byte
        cbw[15] = 0x43.toByte() // Opcode: READ TOC
        cbw[16] = 0x02.toByte() // Byte 1: TIME=1, MSF format
        cbw[17] = 0x00.toByte() // Byte 2: Format 0, Formatted TOC
        cbw[18] = 0x00.toByte()
        cbw[19] = 0x00.toByte()
        cbw[20] = 0x00.toByte()
        cbw[21] = 0x01.toByte() // Byte 6: Start track 1
        cbw[22] = 0x03.toByte() // Bytes 7-8: Allocation length 0x0324 is 804
        cbw[23] = 0x24.toByte()
        cbw[24] = 0x00.toByte()

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to send CBW")
            return null
        }

        // Read Data
        val tocData = ByteArray(804)
        transferred = transport.bulkTransfer(inEndpoint, tocData, tocData.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read READ TOC data")
            return null
        }
        if (transferred < 4) {
            AppLogger.e(TAG, "Short read: $transferred bytes")
            return null
        }
        val responseLength = (tocData.size).coerceAtMost(transferred)

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW")
            return null
        }

        // Validate CSW
        val cswSignature = ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            AppLogger.e(TAG, "Invalid CSW signature")
            return null
        }
        if (csw[12] != 0.toByte()) {
            AppLogger.e(TAG, "CSW indicates command failure: status=${csw[12]}")
            return null
        }

        // Parse TOC Data
        // Byte 0-1: TOC Data Length
        // Byte 2: First Track Number
        // Byte 3: Last Track Number
        val firstTrack = tocData[2].toInt() and 0xFF
        val lastTrack = tocData[3].toInt() and 0xFF

        val tracks = mutableListOf<TrackInfo>()
        var leadOutLba = 0

        // Descriptors start at byte 4
        // Each descriptor is 8 bytes
        var offset = 4
        while (offset + 8 <= responseLength) {
            if (tracks.size >= 99) {
                break
            }
            val controlByte = tocData[offset + 1].toInt() and 0xFF
            val trackNumber = tocData[offset + 2].toInt() and 0xFF
            val m = tocData[offset + 5].toInt() and 0xFF
            val s = tocData[offset + 6].toInt() and 0xFF
            val f = tocData[offset + 7].toInt() and 0xFF

            if (trackNumber == 0) {
                // Ignore invalid
                offset += 8
                continue
            }

            val lba = (m * 60 + s) * 75 + f - 150
            val isAudio = (controlByte and 0x04) == 0

            if (trackNumber == 0xAA) {
                leadOutLba = lba
                break // Lead-out is the last descriptor
            } else {
                tracks.add(TrackInfo(trackNumber, lba, isAudio))
            }

            offset += 8
        }

        if (leadOutLba <= 0) {
            AppLogger.e(TAG, "Invalid or missing lead-out LBA")
            return null
        }

        return DiscToc(
            firstTrack = firstTrack,
            lastTrack = lastTrack,
            tracks = tracks,
            leadOutLba = leadOutLba
        )
    }

    companion object {
        private const val TAG = "ReadTocCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
