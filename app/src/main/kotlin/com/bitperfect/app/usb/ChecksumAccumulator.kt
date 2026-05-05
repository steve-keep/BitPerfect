package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier

internal class ChecksumAccumulator(private val verifier: AccurateRipVerifier) {
    var ripChecksum: Long = 0L
        private set
    var samplePosition: Long = 1L
        private set

    fun accumulate(pcmData: ByteArray?, sectorsToRead: Int, totalSamples: Long) {
        if (pcmData != null) {
            val result = verifier.computeChecksumChunk(pcmData, samplePosition, totalSamples)
            ripChecksum += result.partialChecksum
            samplePosition = result.nextSamplePosition
        } else {
            samplePosition += sectorsToRead.toLong() * 588L
        }
    }

    fun finalise(): Long {
        return verifier.finaliseChecksum(ripChecksum)
    }
}
