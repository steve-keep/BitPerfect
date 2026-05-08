package com.bitperfect.app.usb

import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.services.AccurateRipVerifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RipManagerChecksumTest {

    @Test
    fun `ChecksumAccumulator position threading across chunks`() {
        val verifier = AccurateRipVerifier()
        val totalSamples = 20L * 588L
        val accumulator = ChecksumAccumulator(verifier, totalSamples)

        // Provide 10 sectors of dummy PCM data
        val chunk1Pcm = ByteArray(10 * 2352) { it.toByte() }
        accumulator.accumulate(chunk1Pcm)

        // After 10 sectors (5880 samples), position should be 5881
        assertEquals(1L + (10 * 588L), accumulator.samplePosition)

        // Provide another 10 sectors of dummy PCM data
        val chunk2Pcm = ByteArray(10 * 2352) { (it + 1).toByte() }
        accumulator.accumulate(chunk2Pcm)

        // After another 10 sectors, position should be 11761
        assertEquals(1L + (20 * 588L), accumulator.samplePosition)
    }

    @Test
    fun `TrackRipState WARNING fields populated on mismatch`() {
        val state = TrackRipState(
            trackNumber = 1,
            progress = 1f,
            status = RipStatus.WARNING,
            accurateRipUrl = "http://example.com",
            computedChecksum = 123456L,
            expectedChecksums = listOf(654321L)
        )

        assertEquals(1, state.trackNumber)
        assertEquals(1f, state.progress)
        assertEquals(RipStatus.WARNING, state.status)
        assertEquals("http://example.com", state.accurateRipUrl)
        assertEquals(123456L, state.computedChecksum)
        assertEquals(listOf(654321L), state.expectedChecksums)
    }

    @Test
    fun `Positive offset shifts samples and carries between tracks`() {
        val driveOffset = 10
        val offsetBytes = driveOffset * 4

        // 10 sectors track length to ensure we are accumulating (10 * 588 = 5880 samples)
        // AccurateRip checksum accumulates samples 2941 to totalSamples - 2940.
        // For a 10 sector track, it's exactly 5880 samples, which gives no accumulation because 5880 <= 5880.
        // Let's use 20 sectors (11760 samples) per track. Accumulation window is 2941..8820.
        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        // Simulated track 1 PCM output from drive (shifted by offset)
        // Has a pattern we can trace
        val track1Pcm = ByteArray(trackBytesSize) { (it % 256).toByte() }

        // Track 2 PCM output from drive
        val track2Pcm = ByteArray(trackBytesSize) { ((it + 1) % 256).toByte() }

        // Expected dataForAccumulator for track 1:
        // First `driveOffset` samples are 0 (silence).
        // Remaining `totalSamples - driveOffset` samples are from `track1Pcm`
        val expectedTrack1Data = ByteArray(trackBytesSize)
        // First offsetBytes are already 0
        System.arraycopy(track1Pcm, 0, expectedTrack1Data, offsetBytes, trackBytesSize - offsetBytes)

        // Expected dataForAccumulator for track 2:
        // First `driveOffset` samples are the last `driveOffset` samples from `track1Pcm`
        // Remaining are from `track2Pcm`
        val expectedTrack2Data = ByteArray(trackBytesSize)
        System.arraycopy(track1Pcm, trackBytesSize - offsetBytes, expectedTrack2Data, 0, offsetBytes)
        System.arraycopy(track2Pcm, 0, expectedTrack2Data, offsetBytes, trackBytesSize - offsetBytes)

        val verifier = AccurateRipVerifier()

        // Run expected through verifier to get expected checksums
        val expectedChecksum1 = verifier.computeChecksumChunk(
            expectedTrack1Data, 1, totalSamples,
            isFirstTrack = true, isLastTrack = false
        ).partialChecksum
        val expectedChecksum2 = verifier.computeChecksumChunk(
            expectedTrack2Data, 1, totalSamples,
            isFirstTrack = false, isLastTrack = true
        ).partialChecksum

        // Now simulate the RipManager logic
        var carryBuffer = ByteArray(offsetBytes)

        // --- Track 1 ---
        val trackBytes1 = track1Pcm
        var dataForAccumulator1 = trackBytes1
        val usableBytes1 = trackBytes1.size - offsetBytes
        dataForAccumulator1 = ByteArray(offsetBytes + usableBytes1)
        System.arraycopy(carryBuffer, 0, dataForAccumulator1, 0, offsetBytes)
        System.arraycopy(trackBytes1, 0, dataForAccumulator1, offsetBytes, usableBytes1)
        var nextCarryBuffer1 = trackBytes1.copyOfRange(trackBytes1.size - offsetBytes, trackBytes1.size)
        carryBuffer = nextCarryBuffer1

        val accumulatorT1 = ChecksumAccumulator(
            verifier, totalSamples, driveOffset,
            isFirstTrack = true, isLastTrack = false
        )
        accumulatorT1.accumulate(dataForAccumulator1)
        assertEquals("Track 1 checksum mismatch", expectedChecksum1, accumulatorT1.ripChecksum)

        // --- Track 2 ---
        val trackBytes2 = track2Pcm
        var dataForAccumulator2 = trackBytes2
        val usableBytes2 = trackBytes2.size - offsetBytes
        dataForAccumulator2 = ByteArray(offsetBytes + usableBytes2)
        System.arraycopy(carryBuffer, 0, dataForAccumulator2, 0, offsetBytes)
        System.arraycopy(trackBytes2, 0, dataForAccumulator2, offsetBytes, usableBytes2)

        val accumulatorT2 = ChecksumAccumulator(
            verifier, totalSamples, driveOffset,
            isFirstTrack = false, isLastTrack = true
        )
        accumulatorT2.accumulate(dataForAccumulator2)
        assertEquals("Track 2 checksum mismatch", expectedChecksum2, accumulatorT2.ripChecksum)
    }
}
