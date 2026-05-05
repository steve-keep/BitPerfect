package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier

internal class ChecksumAccumulator(
    private val verifier: AccurateRipVerifier,
    private val totalSamples: Long,
    private val driveOffset: Int = 0
) {
    var ripChecksum: Long = 0L
        private set
    var samplePosition: Long = if (driveOffset < 0) 1L + driveOffset else 1L
        private set

    fun accumulate(pcmData: ByteArray?, sectorsToRead: Int) {
        if (pcmData != null) {
            val adjustedPosition = samplePosition - driveOffset
            val result = verifier.computeChecksumChunk(pcmData, adjustedPosition, totalSamples)
            ripChecksum += result.partialChecksum
            samplePosition += pcmData.size / 4
        } else {
            samplePosition += sectorsToRead.toLong() * 588L
        }
    }

    fun finalise(): Long {
        return verifier.finaliseChecksum(ripChecksum)
    }
}
