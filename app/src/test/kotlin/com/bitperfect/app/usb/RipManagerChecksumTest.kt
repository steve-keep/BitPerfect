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
        val accumulator = ChecksumAccumulator(verifier)

        val totalSamples = 20L * 588L

        // Provide 10 sectors of dummy PCM data
        val chunk1Pcm = ByteArray(10 * 2352) { it.toByte() }
        accumulator.accumulate(chunk1Pcm, 10, totalSamples)

        // After 10 sectors (5880 samples), position should be 5881
        assertEquals(1L + (10 * 588L), accumulator.samplePosition)

        // Provide another 10 sectors of dummy PCM data
        val chunk2Pcm = ByteArray(10 * 2352) { (it + 1).toByte() }
        accumulator.accumulate(chunk2Pcm, 10, totalSamples)

        // After another 10 sectors, position should be 11761
        assertEquals(1L + (20 * 588L), accumulator.samplePosition)
    }

    @Test
    fun `ChecksumAccumulator silence advancement`() {
        val verifier = AccurateRipVerifier()
        val accumulator = ChecksumAccumulator(verifier)

        val totalSamples = 20L * 588L

        // Simulate read failure for 10 sectors
        accumulator.accumulate(null, 10, totalSamples)

        // Position should still advance by 10 sectors' worth of samples
        assertEquals(1L + (10 * 588L), accumulator.samplePosition)

        // Provide 10 sectors of dummy PCM data
        val chunk2Pcm = ByteArray(10 * 2352) { it.toByte() }
        accumulator.accumulate(chunk2Pcm, 10, totalSamples)

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
}
