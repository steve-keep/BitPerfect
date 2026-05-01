package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadTocCommand(
    private val transport: UsbTransport,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint
) {
    fun execute(tag: Int = 3): Pair<DiscToc, ByteArray>? {
        // CBW: 31 bytes
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE) // dCBWSignature
        buffer.putInt(tag)           // dCBWTag (can be anything unique)
        buffer.putInt(804)           // dCBWDataTransferLength (READ TOC needs 804 bytes max)
        buffer.put(0x80.toByte())    // bmCBWFlags: 0x80 for IN
        buffer.put(0)                // bCBWLUN
        buffer.put(10)               // bCBWCBLength (READ TOC command length)

        // SCSI READ TOC Command Block (10 bytes)
        buffer.put(0x43)             // Opcode: READ TOC/PMA/ATIP
        buffer.put(0)                // MSF bit 0 (LBA format)
        buffer.put(0)                // Format 0b0000
        buffer.put(0)                // Reserved
        buffer.put(0)                // Reserved
        buffer.put(0)                // Reserved
        buffer.put(0)                // Track/Session Number = 0

        // Byte 7: Allocation Length MSB
        // Byte 8: Allocation Length LSB
        // 804 = 0x0324
        buffer.put(0x03)
        buffer.put(0x24)

        // Byte 9: Control
        buffer.put(0)


        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to send CBW")
            return null
        }

        // Read Data Phase 1: 4-byte header
        val headerBuf = ByteArray(4)
        val headerRead = transport.bulkTransfer(inEndpoint, headerBuf, 4, 5000)
        if (headerRead < 4) {
            AppLogger.e(TAG, "Failed to read TOC header")
            return null
        }
        val tocDataLengthField = ((headerBuf[0].toInt() and 0xFF) shl 8) or (headerBuf[1].toInt() and 0xFF)
        val expectedTotal = minOf(tocDataLengthField + 2, 804)

        // Read Data Phase 2: remaining bytes
        val tocData = ByteArray(804)
        System.arraycopy(headerBuf, 0, tocData, 0, 4)
        val remaining = expectedTotal - 4
        val bodyBuf = ByteArray(remaining)
        val bodyRead = transport.bulkTransferFully(inEndpoint, bodyBuf, remaining, 5000)
        if (bodyRead < 0) {
            AppLogger.e(TAG, "Failed to read TOC body")
            return null
        }
        System.arraycopy(bodyBuf, 0, tocData, 4, bodyRead)
        val totalTocRead = 4 + bodyRead

        AppLogger.d(TAG, "RAW TOC: ${tocData.take(totalTocRead).joinToString(" ") { "%02x".format(it) }}")

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW")
            return null
        }

        // Validate CSW
        val cswSignature = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            AppLogger.e(TAG, "Invalid CSW signature")
            return null
        }
        if (csw[12] != 0.toByte()) {
            AppLogger.e(TAG, "CSW indicates command failure: status=${csw[12]}")
            return null
        }

        // Parse TOC Data
        val dataBuffer = ByteBuffer.wrap(tocData).order(ByteOrder.BIG_ENDIAN)
        val tocDataLength = dataBuffer.getShort(0).toInt() and 0xFFFF

        val entries = mutableListOf<TocEntry>()
        var leadOutLba = 0

        // Entries start at byte 4. Each entry is 8 bytes.
        // Number of entries = (tocDataLength - 2) / 8
        val entryCount = (tocDataLength - 2) / 8

        for (i in 0 until entryCount) {
            val offset = 4 + (i * 8)
            val adrControl = tocData[offset + 1].toInt()
            val trackNumber = tocData[offset + 2].toInt() and 0xFF
            val lba = dataBuffer.getInt(offset + 4)

            if (trackNumber == 0xAA) {
                leadOutLba = lba
            } else {
                // Check if audio track (Control bit 2 should be 0)
                if ((adrControl and 0x04) == 0) {
                    entries.add(TocEntry(trackNumber, lba))
                }
            }
        }

        // Normalise to 150-based LBAs (Redbook standard).
        // Some drives (e.g. ASUS SDRW-08D2S-U) return 0-based LBAs with track 1 at LBA 0.
        // MusicBrainz, AccurateRip, and the ripping pipeline all expect 150-based offsets.
        val pregapOffset = if (entries.firstOrNull()?.lba == 0) 150 else 0
        val normalisedEntries = if (pregapOffset == 0) entries else entries.map { it.copy(lba = it.lba + pregapOffset) }
        val normalisedLeadOut = leadOutLba + pregapOffset
        return Pair(DiscToc(normalisedEntries, normalisedLeadOut), tocData.copyOf(totalTocRead))
    }

    companion object {
        private const val TAG = "ReadTocCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
