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
    fun execute(): Pair<DiscToc, ByteArray>? {
        val tag = transport.nextTag()
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

        // Read TOC data in a single buffered read
        val tocData = ByteArray(804)
        val totalTocRead = transport.bulkTransfer(inEndpoint, tocData, 804, 5000)
        if (totalTocRead < 4) {
            AppLogger.e(TAG, "Failed to read TOC data: only $totalTocRead bytes received")
            return null
        }

        AppLogger.d(TAG, "RAW TOC: ${tocData.take(totalTocRead).joinToString(" ") { "%02x".format(it) }}")

        // Read CSW (Command Status Wrapper)
        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW")
            return null
        }

        // Validate CSW
        val cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val cswSignature = cswBuffer.getInt(0)
        if (cswSignature != CSW_SIGNATURE) {
            AppLogger.e(TAG, "Invalid CSW signature")
            return null
        }
        val cswTag = cswBuffer.getInt(4)
        if (cswTag != tag) {
            AppLogger.e(TAG, "CSW tag mismatch: expected $tag, got $cswTag")
            return null
        }
        if (csw[12] != 0.toByte()) {
            AppLogger.e(TAG, "CSW indicates command failure: status=${csw[12]}")
            return null
        }

        // Parse TOC Data
        val tocLength = ((tocData[0].toInt() and 0xFF) shl 8) or (tocData[1].toInt() and 0xFF)

        if (tocLength < 2) {
            AppLogger.e(TAG, "Invalid TOC length")
            return null
        }

        if ((tocLength - 2) % 8 != 0) {
            AppLogger.e(TAG, "TOC descriptor alignment invalid")
            return null
        }

        if (tocLength + 2 > totalTocRead) {
            AppLogger.e(TAG, "TOC length exceeds received bytes")
            return null
        }

        AppLogger.d(TAG, "TOC Claimed Length: $tocLength, Actual Bytes Read: $totalTocRead")

        data class RawTocEntry(val trackNumber: Int, val lba: Int, val ctrl: Int)
        val allEntries = mutableListOf<RawTocEntry>()
        var leadOutLba: Int? = null

        var offset = 4
        while (offset + 8 <= totalTocRead && offset < tocLength + 2) {
            val adrControl = tocData[offset + 1].toInt() and 0xFF
            val trackNumber = tocData[offset + 2].toInt() and 0xFF
            val lba = ((tocData[offset + 4].toInt() and 0xFF) shl 24) or
                      ((tocData[offset + 5].toInt() and 0xFF) shl 16) or
                      ((tocData[offset + 6].toInt() and 0xFF) shl 8) or
                      (tocData[offset + 7].toInt() and 0xFF)

            AppLogger.d(TAG, "TOC track=$trackNumber lba=$lba ctrl=$adrControl")

            if (trackNumber == 0xAA) {
                leadOutLba = lba
            } else {
                allEntries.add(RawTocEntry(trackNumber, lba, adrControl))
            }

            offset += 8
        }

        val isDataTrack: (Int) -> Boolean = { ctrl -> (ctrl and 0x04) != 0 }
        val lastAudioTrackIndex = allEntries.indexOfLast { !isDataTrack(it.ctrl) }
        val isCdExtra = lastAudioTrackIndex in 0 until allEntries.size - 1 && isDataTrack(allEntries.last().ctrl)

        val audioEntries = if (lastAudioTrackIndex >= 0) {
            allEntries.take(lastAudioTrackIndex + 1).map { TocEntry(it.trackNumber, it.lba) }
        } else {
            emptyList()
        }

        AppLogger.d(TAG, "Audio track count=${audioEntries.size}")
        if (audioEntries.isNotEmpty()) {
            AppLogger.d(TAG, "Last audio track=${audioEntries.last().trackNumber}")
        }

        var audioLeadOutLba: Int? = null

        val hasDataTrack = allEntries.any { isDataTrack(it.ctrl) }
        if (hasDataTrack) {
            val lastAudioLba = audioEntries.lastOrNull()?.lba
            val sessionLeadout = fetchAudioLeadOutFromFullToc(lastAudioLba, leadOutLba)
            AppLogger.d(TAG, "Raw leadout=$leadOutLba")
            AppLogger.d(TAG, "Session leadout=$sessionLeadout")
            if (sessionLeadout != null && sessionLeadout != leadOutLba) {
                AppLogger.w(TAG, "Audio-session leadout mismatch: physical=$leadOutLba audio=$sessionLeadout")
                audioLeadOutLba = sessionLeadout
                AppLogger.d(TAG, "Selected MB leadout source=full_toc_a2")
            } else if (leadOutLba == null) {
                leadOutLba = sessionLeadout
            }
        }

        if (leadOutLba == null) {
            AppLogger.e(TAG, "Missing physical leadout")
            return null
        }
        AppLogger.d(TAG, "Final MB leadout=${audioLeadOutLba ?: leadOutLba}")

        for (i in 1 until allEntries.size) {
            if (allEntries[i].lba <= allEntries[i - 1].lba) {
                AppLogger.e(TAG, "Non-monotonic track LBA")
                return null
            }
        }

        if (audioEntries.isNotEmpty() && leadOutLba!! <= audioEntries.last().lba) {
            AppLogger.e(TAG, "Invalid leadout ordering against audio tracks")
            return null
        }

        val trackSet = mutableSetOf<Int>()
        for (entry in allEntries) {
            if (!trackSet.add(entry.trackNumber)) {
                AppLogger.e(TAG, "Duplicate track number: ${entry.trackNumber}")
                return null
            }
        }

        // Normalise to 150-based LBAs (Redbook standard).
        // Some drives (e.g. ASUS SDRW-08D2S-U) return 0-based LBAs with track 1 at LBA 0.
        // MusicBrainz, AccurateRip, and the ripping pipeline all expect 150-based offsets.
        AppLogger.d(TAG, "Track1 raw=${audioEntries.firstOrNull()?.lba}")
        val pregapOffset = if (audioEntries.firstOrNull()?.lba == 0) 150 else 0
        AppLogger.d(TAG, "Pregap adjustment=$pregapOffset")
        val normalisedEntries = if (pregapOffset == 0) audioEntries else audioEntries.map { it.copy(lba = it.lba + pregapOffset) }
        val normalisedLeadOut = leadOutLba!! + pregapOffset
        val normalisedAudioLeadOut = audioLeadOutLba?.let { it + pregapOffset }

        val pair = Pair(DiscToc(normalisedEntries, normalisedLeadOut, pregapOffset, normalisedAudioLeadOut), tocData.copyOf(totalTocRead))
        AppLogger.d(TAG, "Generated MB Disc ID: ${com.bitperfect.core.utils.computeMusicBrainzDiscId(pair.first)}")
        return pair
    }

    private fun fetchAudioLeadOutFromFullToc(lastAudioTrackLba: Int?, physicalLeadOutLba: Int?): Int? {
        AppLogger.d(TAG, "Fetching Full TOC (Format=0x02) to determine audio session leadout")
        val tag = transport.nextTag()
        val allocLen = 2048
        val cbw = ByteArray(31)
        val buffer = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CBW_SIGNATURE)
        buffer.putInt(tag)
        buffer.putInt(allocLen)      // dCBWDataTransferLength
        buffer.put(0x80.toByte())    // bmCBWFlags
        buffer.put(0)                // bCBWLUN
        buffer.put(10)               // bCBWCBLength

        // SCSI READ TOC Command Block (10 bytes)
        buffer.put(0x43)             // Opcode: READ TOC/PMA/ATIP
        buffer.put(0x02)             // MSF=1
        buffer.put(2)                // Format 0b0010 (Full TOC / Format 0x02)
        buffer.put(0)                // Reserved
        buffer.put(0)                // Reserved
        buffer.put(0)                // Reserved
        buffer.put(1)                // Session Number = 1
        buffer.put((allocLen shr 8).toByte())
        buffer.put((allocLen and 0xFF).toByte())
        buffer.put(0)

        // Send CBW
        var transferred = transport.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to send CBW for Full TOC")
            return null
        }

        val tocData = ByteArray(allocLen)
        val totalTocRead = transport.bulkTransferFully(inEndpoint, tocData, allocLen, 5000)
        if (totalTocRead < 4) {
            AppLogger.e(TAG, "Failed to read Full TOC data")
            return null
        }

        val csw = ByteArray(13)
        transferred = transport.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (transferred < 0) {
            AppLogger.e(TAG, "Failed to read CSW for Full TOC")
            return null
        }

        val cswBuffer = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (cswBuffer.getInt(0) != CSW_SIGNATURE || cswBuffer.getInt(4) != tag || csw[12] != 0.toByte()) {
            AppLogger.e(TAG, "Invalid or failed CSW for Full TOC")
            return null
        }

        val tocLength = ((tocData[0].toInt() and 0xFF) shl 8) or (tocData[1].toInt() and 0xFF)

        if (tocLength < 2) {
            AppLogger.e(TAG, "Invalid Full TOC length")
            return null
        }

        if ((tocLength - 2) % 11 != 0) {
            AppLogger.e(TAG, "Full TOC descriptor alignment invalid")
            return null
        }

        if (tocLength + 2 > totalTocRead) {
            AppLogger.e(TAG, "Full TOC length exceeds received bytes")
            return null
        }

        var offset = 4
        val audioSessions = mutableSetOf<Int>()

        // Scan Full TOC descriptors to identify audio sessions
        while (offset + 11 <= totalTocRead && offset < tocLength + 2) {
            val session = tocData[offset].toInt() and 0xFF
            val control = tocData[offset + 1].toInt() and 0xFF
            val point = tocData[offset + 3].toInt() and 0xFF

            if (point in 0x01..0x63) {
                val isData = (control and 0x04) != 0
                if (!isData) {
                    audioSessions.add(session)
                }
            }
            offset += 11
        }

        if (audioSessions.isEmpty()) {
            AppLogger.e(TAG, "No audio sessions found in Full TOC")
            return null
        }
        val targetSession = audioSessions.maxOrNull()!!
        AppLogger.d(TAG, "Identified audio sessions: $audioSessions. Selected target session=$targetSession")

        offset = 4
        while (offset + 11 <= totalTocRead && offset < tocLength + 2) {
            val session = tocData[offset].toInt() and 0xFF
            val point = tocData[offset + 3].toInt() and 0xFF

            if (session == targetSession && point == 0xA2) {
                val pmin = tocData[offset + 8].toInt() and 0xFF
                val psec = tocData[offset + 9].toInt() and 0xFF
                val pframe = tocData[offset + 10].toInt() and 0xFF

                val lba = ((pmin * 60) + psec) * 75 + pframe
                AppLogger.d(TAG, "Session $targetSession A2 leadout MSF=$pmin:$psec:$pframe lba=$lba")

                if (lastAudioTrackLba != null && lba <= lastAudioTrackLba) {
                    AppLogger.w(TAG, "Extracted Full TOC A2 leadout ($lba) <= last audio track LBA ($lastAudioTrackLba)")
                    return null
                }
                if (physicalLeadOutLba != null && lba >= physicalLeadOutLba) {
                    AppLogger.w(TAG, "Extracted Full TOC A2 leadout ($lba) >= physical leadout ($physicalLeadOutLba)")
                    return null
                }

                return lba
            }

            offset += 11
        }

        AppLogger.e(TAG, "Failed to find 0xA2 entry for target session $targetSession in Full TOC")
        return null
    }
    companion object {
        private const val TAG = "ReadTocCommand"
        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}
