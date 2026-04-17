package com.bitperfect.core.engine

import com.bitperfect.driver.IScsiDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TocReader(private val scsiDriver: IScsiDriver) {

    fun readToc(fd: Int, endpointIn: Int, endpointOut: Int): DiscToc? {
        // READ TOC command (0x43)
        // Byte 1: MSF bit = 1 (bit 1) -> 0x02
        // Byte 2: Format = 0 (standard TOC)
        // Byte 7-8: Allocation Length (804 bytes for 100 tracks + header)
        val command = byteArrayOf(
            0x43.toByte(),
            0x02.toByte(), // MSF bit set
            0, 0, 0, 0, 0,
            0x03.toByte(), 0x24.toByte(), // 804 bytes
            0
        )

        val response = scsiDriver.executeScsiCommand(fd, command, 804, endpointIn, endpointOut)
        if (response == null || response.size < 4) return null

        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        val dataLength = buffer.getShort(0).toInt() and 0xFFFF
        if (dataLength < 2) return null

        val firstTrack = response[2].toInt() and 0xFF
        val lastTrack = response[3].toInt() and 0xFF

        val trackEntries = mutableListOf<TrackEntry>()
        var leadOutLba = 0

        for (i in 0 until (lastTrack - firstTrack + 2)) {
            val base = 4 + i * 8
            if (base + 8 > response.size) break

            val controlAdr = response[base + 1].toInt() and 0xFF
            val control = (controlAdr shr 4) and 0x0F

            val trackNum = response[base + 2].toInt() and 0xFF

            val m = response[base + 5].toInt() and 0xFF
            val s = response[base + 6].toInt() and 0xFF
            val f = response[base + 7].toInt() and 0xFF

            val lba = msfToLba(m, s, f)
            val isAudio = (control and 0x04) == 0

            if (trackNum == 0xAA) {
                leadOutLba = lba
            } else if (trackNum in firstTrack..lastTrack) {
                trackEntries.add(TrackEntry(
                    number = trackNum,
                    startLba = lba,
                    durationSectors = 0, // Will calculate below
                    isAudio = isAudio
                ))
            }
        }

        // Calculate durations
        val sortedTracks = trackEntries.sortedBy { it.number }
        val tracksWithDuration = sortedTracks.mapIndexed { index, track ->
            val nextStart = if (index < sortedTracks.size - 1) {
                sortedTracks[index + 1].startLba
            } else {
                leadOutLba
            }
            track.copy(durationSectors = nextStart - track.startLba)
        }

        return DiscToc(
            firstTrack = firstTrack,
            lastTrack = lastTrack,
            tracks = tracksWithDuration,
            leadOutLba = leadOutLba,
            totalDurationSectors = leadOutLba - (tracksWithDuration.firstOrNull()?.startLba ?: 0)
        )
    }

    private fun msfToLba(m: Int, s: Int, f: Int): Int {
        return (m * 4500 + s * 75 + f) - 150
    }
}
