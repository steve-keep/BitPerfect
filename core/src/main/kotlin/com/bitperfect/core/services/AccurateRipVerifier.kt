package com.bitperfect.core.services

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AccurateRipTrackMetadata(
    val checksum: Long,
    val confidence: Int
)

class AccurateRipVerifier {
    fun parseAccurateRipResponse(responseBytes: ByteArray): Map<Int, List<AccurateRipTrackMetadata>> {
        val tracksInfo = mutableMapOf<Int, MutableList<AccurateRipTrackMetadata>>()
        val buffer = ByteBuffer.wrap(responseBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= 9) {
            val trackCount = buffer.get().toInt() and 0xFF
            val discId1 = buffer.getInt().toLong() and 0xFFFFFFFFL
            val discId2 = buffer.getInt().toLong() and 0xFFFFFFFFL

            AppLogger.d("AccurateRipVerifier", "Parsed disc entry header: discId1=${String.format("%08x", discId1)}, discId2=${String.format("%08x", discId2)}")

            if (buffer.remaining() < trackCount * 9) {
                AppLogger.w("AccurateRipVerifier", "Truncated AccurateRip response: expected ${trackCount * 9} bytes for $trackCount tracks, but only ${buffer.remaining()} bytes remaining")
                break
            }

            for (i in 0 until trackCount) {
                val confidence = buffer.get().toInt() and 0xFF
                val crcV1 = buffer.getInt().toLong() and 0xFFFFFFFFL
                val crcV2 = buffer.getInt().toLong() and 0xFFFFFFFFL // AR v2 checksum — stored for future use, not currently matched

                val trackNumber = i + 1
                tracksInfo.getOrPut(trackNumber) { mutableListOf() }.add(
                    AccurateRipTrackMetadata(crcV1, confidence)
                )
            }
            AppLogger.d("AccurateRipVerifier", "Parsed $trackCount tracks for this disc entry")
        }
        return tracksInfo
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

            if (currentSamplePos > skipStart && currentSamplePos <= totalSamples - skipEnd) {
                partialChecksum += sampleValue * currentSamplePos
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
