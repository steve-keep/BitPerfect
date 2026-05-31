package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier

internal class ChecksumAccumulator(
    private val totalSamples: Long,
    private val isFirstTrack: Boolean = false,
    private val isLastTrack: Boolean = false
) {
    var ripChecksumV1: Long = 0L
        private set
    var ripChecksumV2: Long = 0L
        private set
    var samplePosition: Long = 1L
        private set

    fun getTotalProcessedBytes(): Long {
        return (samplePosition - 1L) * 4L
    }

    fun accumulate(pcmData: ByteArray) {
        val skipStart = if (isFirstTrack) 2940L else 0L
        val skipEnd   = if (isLastTrack)  2940L else 0L

        var currentSamplePos = samplePosition
        var partialV1 = 0L
        var partialV2 = 0L

        for (i in 0 until (pcmData.size / 4) * 4 step 4) {
            val sample = ((pcmData[i].toLong() and 0xFF) or
                         ((pcmData[i+1].toLong() and 0xFF) shl 8) or
                         ((pcmData[i+2].toLong() and 0xFF) shl 16) or
                         ((pcmData[i+3].toLong() and 0xFF) shl 24))

            val sampleValue = sample and 0xFFFFFFFFL

            if (currentSamplePos > skipStart && currentSamplePos <= totalSamples - skipEnd) {
                // V1 accumulation
                partialV1 = (partialV1 + sampleValue * currentSamplePos) and 0xFFFFFFFFL

                // V2 accumulation
                val calcCrcNew = sampleValue * currentSamplePos
                val lo = calcCrcNew and 0xFFFFFFFFL
                val hi = (calcCrcNew ushr 32) and 0xFFFFFFFFL
                partialV2 = (partialV2 + hi + lo) and 0xFFFFFFFFL
            }
            currentSamplePos++
        }

        ripChecksumV1 = (ripChecksumV1 + partialV1) and 0xFFFFFFFFL
        ripChecksumV2 = (ripChecksumV2 + partialV2) and 0xFFFFFFFFL
        samplePosition = currentSamplePos
    }

    // Return a Pair of (V1, V2) checksums
    fun finalise(): Pair<Long, Long> {
        return Pair(
            ripChecksumV1 and 0xFFFFFFFFL,
            ripChecksumV2 and 0xFFFFFFFFL
        )
    }
}
