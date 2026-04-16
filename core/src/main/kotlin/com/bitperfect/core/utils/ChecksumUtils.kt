package com.bitperfect.core.utils

import java.util.zip.CRC32

object ChecksumUtils {
    /**
     * Calculates CRC32 of a byte array.
     */
    fun calculateCrc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * Calculates CRC32 of a portion of a byte array.
     */
    fun calculateCrc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }

    /**
     * Calculates the AccurateRip CRC contribution for a sector.
     *
     * @param sector Data for one sector (2352 bytes).
     * @param sectorIndex Index of the sector in the track (0-indexed).
     * @param trackSectors Total number of sectors in the track.
     * @param isFirstTrack Whether this is the first track of the disc.
     * @param isLastTrack Whether this is the last track of the disc.
     */
    fun calculateAccurateRipCrc(
        sector: ByteArray,
        sectorIndex: Int,
        trackSectors: Int,
        isFirstTrack: Boolean,
        isLastTrack: Boolean
    ): Long {
        if (isFirstTrack && sectorIndex < 4) return 0
        if (isLastTrack && sectorIndex >= trackSectors - 5) return 0

        val framesPerSector = 588 // 2352 / 4

        if (isFirstTrack && sectorIndex == 4) {
            val i = framesPerSector - 1
            val b0 = sector[i * 4].toLong() and 0xFF
            val b1 = sector[i * 4 + 1].toLong() and 0xFF
            val b2 = sector[i * 4 + 2].toLong() and 0xFF
            val b3 = sector[i * 4 + 3].toLong() and 0xFF
            val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0

            return (framesPerSector * (4 + 1) * sample) and 0xFFFFFFFFL
        }

        var checksum = 0L
        var frameOffset = (sectorIndex * framesPerSector).toLong()

        for (i in 0 until framesPerSector) {
            val b0 = sector[i * 4].toLong() and 0xFF
            val b1 = sector[i * 4 + 1].toLong() and 0xFF
            val b2 = sector[i * 4 + 2].toLong() and 0xFF
            val b3 = sector[i * 4 + 3].toLong() and 0xFF

            val sample = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0

            checksum += sample * (++frameOffset)
        }

        return checksum and 0xFFFFFFFFL
    }
}
