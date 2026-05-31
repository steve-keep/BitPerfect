package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier
import java.util.zip.CRC32

internal class ChecksumAccumulator(
    private val totalSamples: Long,
    private val isFirstTrack: Boolean = false,
    private val isLastTrack: Boolean = false
) {
    var ripChecksumV1: Long = 0L
        private set
    var samplePosition: Long = 1L
        private set

    private val crcV2 = CRC32()

    fun getTotalProcessedBytes(): Long {
        return (samplePosition - 1L) * 4L
    }

    fun accumulate(pcmData: ByteArray) {
        val skipStart = if (isFirstTrack) 2940L else 0L
        val skipEnd   = if (isLastTrack)  2940L else 0L

        var currentSamplePos = samplePosition
        var partialV1 = 0L

        val bytes = ByteArray(4)

        for (i in 0 until (pcmData.size / 4) * 4 step 4) {
            val sample = ((pcmData[i].toLong() and 0xFF) or
                         ((pcmData[i+1].toLong() and 0xFF) shl 8) or
                         ((pcmData[i+2].toLong() and 0xFF) shl 16) or
                         ((pcmData[i+3].toLong() and 0xFF) shl 24))

            val sampleValue = sample and 0xFFFFFFFFL

            // V2 accumulation
            val weighted = (sampleValue * currentSamplePos) and 0xFFFFFFFFL
            bytes[0] = (weighted and 0xFF).toByte()
            bytes[1] = ((weighted shr 8) and 0xFF).toByte()
            bytes[2] = ((weighted shr 16) and 0xFF).toByte()
            bytes[3] = ((weighted shr 24) and 0xFF).toByte()
            crcV2.update(bytes)

            if (currentSamplePos > skipStart && currentSamplePos <= totalSamples - skipEnd) {
                // V1 accumulation
                partialV1 = (partialV1 + sampleValue * currentSamplePos) and 0xFFFFFFFFL
            }
            currentSamplePos++
        }

        ripChecksumV1 = (ripChecksumV1 + partialV1) and 0xFFFFFFFFL
        samplePosition = currentSamplePos
    }

    // Return a Pair of (V1, V2) checksums
    fun finalise(): Pair<Long, Long> {
        return Pair(
            ripChecksumV1 and 0xFFFFFFFFL,
            crcV2.value and 0xFFFFFFFFL
        )
    }
}
