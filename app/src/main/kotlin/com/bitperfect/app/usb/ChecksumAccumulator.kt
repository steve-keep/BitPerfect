package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier

internal class ChecksumAccumulator(
    private val verifier: AccurateRipVerifier,
    private val totalSamples: Long,
    private val isFirstTrack: Boolean = false,
    private val isLastTrack: Boolean = false
) {
    var ripChecksum: Long = 0L
        private set
    var samplePosition: Long = 1L
        private set

    fun accumulate(pcmData: ByteArray) {
        val result = verifier.computeChecksumChunk(
            pcmData,
            samplePosition,
            totalSamples,
            isFirstTrack,
            isLastTrack
        )
        ripChecksum = (ripChecksum + result.partialChecksum) and 0xFFFFFFFFL
        samplePosition = result.nextSamplePosition
    }

    fun finalise(): Long {
        return verifier.finaliseChecksum(ripChecksum)
    }
}
