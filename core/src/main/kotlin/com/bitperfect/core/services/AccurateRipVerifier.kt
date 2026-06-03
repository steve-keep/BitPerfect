package com.bitperfect.core.services

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AccurateRipTrackMetadata(
    val crcV1: Long,
    val crcV2: Long?,
    val confidence: Int
)

data class AccurateRipDiscPressing(
    val discId1: Long,
    val discId2: Long,
    val tracks: Map<Int, AccurateRipTrackMetadata>
)

class AccurateRipVerifier {
    fun parseAccurateRipResponse(responseBytes: ByteArray): List<AccurateRipDiscPressing> {
        val pressings = mutableListOf<AccurateRipDiscPressing>()
        // To determine if a file is V1 (5 bytes per track) or V2 (9 bytes per track),
        // we can do a quick dry-run parse or check the format.
        // A simple way is to check if it parses cleanly as V2.
        var isV2 = true
        var dryRunBuffer = ByteBuffer.wrap(responseBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (dryRunBuffer.remaining() >= 13) {
            val trackCount = dryRunBuffer.get().toInt() and 0xFF
            dryRunBuffer.position(dryRunBuffer.position() + 12) // Skip discId1, discId2, CDDB
            if (dryRunBuffer.remaining() < trackCount * 9) {
                isV2 = false
                break
            }
            dryRunBuffer.position(dryRunBuffer.position() + trackCount * 9)
        }
        if (dryRunBuffer.remaining() != 0) {
            isV2 = false
        }

        val trackRecordSize = if (isV2) 9 else 5

        val buffer = ByteBuffer.wrap(responseBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= 13) {
            val trackCount = buffer.get().toInt() and 0xFF
            val discId1 = buffer.getInt().toLong() and 0xFFFFFFFFL
            val discId2 = buffer.getInt().toLong() and 0xFFFFFFFFL
            buffer.getInt() // consume CDDB / discId3

            AppLogger.d("AccurateRipVerifier", "Parsed disc entry header: discId1=${String.format("%08x", discId1)}, discId2=${String.format("%08x", discId2)}")

            if (trackCount == 0) {
                AppLogger.w("AccurateRipVerifier", "Zero track count in AccurateRip response")
                break
            }

            if (buffer.remaining() < trackCount * trackRecordSize) {
                AppLogger.w("AccurateRipVerifier", "Truncated AccurateRip response: remaining ${buffer.remaining()} bytes not enough for $trackCount tracks of size $trackRecordSize")
                break
            }

            val tracksInfo = mutableMapOf<Int, AccurateRipTrackMetadata>()
            for (i in 0 until trackCount) {
                val confidence = buffer.get().toInt() and 0xFF
                val crcV1 = buffer.getInt().toLong() and 0xFFFFFFFFL
                val crcV2 = if (isV2) buffer.getInt().toLong() and 0xFFFFFFFFL else null

                val trackNumber = i + 1
                tracksInfo[trackNumber] = AccurateRipTrackMetadata(crcV1, crcV2, confidence)
            }

            pressings.add(AccurateRipDiscPressing(discId1, discId2, tracksInfo))
            AppLogger.d("AccurateRipVerifier", "Parsed $trackCount tracks for this disc entry")
        }
        return pressings
    }

    @Deprecated("Replaced by computeChecksumChunk — remove after RipManager is updated in Chunk 2")
    fun computeChecksum(pcmData: ByteArray): Long = 0L

    /**
     * Accumulates the AccurateRip v1 checksum contribution for [pcmData].
     *
     * @param pcmData        Raw 16-bit stereo PCM, little-endian, 4 bytes per sample.
     * @param samplePosition The 1-based index of the first sample in [pcmData] within the track.
     * @param totalSamples   Total number of samples in the full track (used to determine the
     *                       exclusion window at the end of the track).
     * @return A [ChunkChecksumResult] containing the partial checksum contribution and the
     *         next sample position to pass to the following chunk.
     */
    fun computeChecksumChunk(
        pcmData: ByteArray,
        samplePosition: Long,
        totalSamples: Long,
        isFirstTrack: Boolean = false,
        isLastTrack: Boolean = false
    ): ChunkChecksumResult {
        var partialChecksum = 0L
        var currentSamplePos = samplePosition

        val skipStart = if (isFirstTrack) 2940L else 0L
        val skipEnd   = if (isLastTrack)  2940L else 0L

        for (i in 0 until (pcmData.size / 4) * 4 step 4) {
            val sample = ((pcmData[i].toLong() and 0xFF) or
                         ((pcmData[i+1].toLong() and 0xFF) shl 8) or
                         ((pcmData[i+2].toLong() and 0xFF) shl 16) or
                         ((pcmData[i+3].toLong() and 0xFF) shl 24))

            val sampleValue = sample and 0xFFFFFFFFL

            if (currentSamplePos >= skipStart && currentSamplePos <= totalSamples - skipEnd) {
                partialChecksum = (partialChecksum + sampleValue * currentSamplePos) and 0xFFFFFFFFL
            }
            currentSamplePos++
        }

        return ChunkChecksumResult(partialChecksum, currentSamplePos)
    }

    /**
     * Masks a raw accumulated checksum to 32 bits, as required by AccurateRip.
     */
    fun finaliseChecksum(accumulated: Long): Long = accumulated and 0xFFFFFFFFL
}

data class ChunkChecksumResult(
    val partialChecksum: Long,
    val nextSamplePosition: Long
)
