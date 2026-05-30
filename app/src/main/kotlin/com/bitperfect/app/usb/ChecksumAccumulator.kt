package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier
import java.util.zip.CRC32

internal class ChecksumAccumulator(
    private val verifier: AccurateRipVerifier,
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
        // --- V2 Accumulation (Standard CRC32 over valid window) ---
        val skipStartBytes = if (isFirstTrack) 2940L * 4L else 0L
        val skipEndBytes = if (isLastTrack) 2940L * 4L else 0L
        val totalBytes = totalSamples * 4L

        val currentStartByte = (samplePosition - 1L) * 4L
        val currentEndByte = currentStartByte + pcmData.size

        val validStartByte = maxOf(currentStartByte, skipStartBytes)
        val validEndByte = minOf(currentEndByte, totalBytes - skipEndBytes)

        if (validStartByte < validEndByte) {
            val offset = (validStartByte - currentStartByte).toInt()
            val length = (validEndByte - validStartByte).toInt()
            crcV2.update(pcmData, offset, length)
        }

        // --- V1 Accumulation ---
        val result = verifier.computeChecksumChunk(
            pcmData,
            samplePosition,
            totalSamples,
            isFirstTrack,
            isLastTrack
        )
        ripChecksumV1 = (ripChecksumV1 + result.partialChecksum) and 0xFFFFFFFFL
        samplePosition = result.nextSamplePosition
    }

    // Return a Pair of (V1, V2) checksums
    fun finalise(): Pair<Long, Long> {
        return Pair(
            verifier.finaliseChecksum(ripChecksumV1),
            crcV2.value and 0xFFFFFFFFL
        )
    }
}
