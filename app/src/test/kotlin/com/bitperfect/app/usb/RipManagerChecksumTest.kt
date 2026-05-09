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

        val chunk1Pcm = ByteArray(10 * 2352) { it.toByte() }
        accumulator.accumulate(chunk1Pcm)
        assertEquals(1L + (10 * 588L), accumulator.samplePosition)

        val chunk2Pcm = ByteArray(10 * 2352) { (it + 1).toByte() }
        accumulator.accumulate(chunk2Pcm)
        assertEquals(1L + (20 * 588L), accumulator.samplePosition)
    }

    @Test
    fun `cdparanoia model with positive offset`() {
        // +667 offset -> tocOffset = 1, sampleOffset = 79, skipBytes = 316
        val driveOffset = 667
        val tocOffset = 1
        val sampleOffset = 79
        val skipBytes = 316

        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        // Track 1
        val t1PhysicalMain = ByteArray(trackBytesSize) { (it % 256).toByte() }
        val t1PhysicalOvershoot = ByteArray(2352) { ((it + 10) % 256).toByte() }

        // Track 2
        // Main loop reads trackBytesSize - 2352 (since sectorsRead starts at 1)
        val t2PhysicalMain = ByteArray(trackBytesSize - 2352) { ((it + 20) % 256).toByte() }
        val t2PhysicalOvershoot = ByteArray(2352) { ((it + 30) % 256).toByte() }

        // Track 1 expected continuous PCM
        val expectedT1 = ByteArray(trackBytesSize)
        System.arraycopy(t1PhysicalMain, skipBytes, expectedT1, 0, trackBytesSize - skipBytes)
        System.arraycopy(t1PhysicalOvershoot, 0, expectedT1, trackBytesSize - skipBytes, skipBytes)

        // Track 2 expected continuous PCM
        val expectedT2 = ByteArray(trackBytesSize)
        val overreadBuffer = t1PhysicalOvershoot.copyOfRange(skipBytes, 2352)
        System.arraycopy(overreadBuffer, 0, expectedT2, 0, overreadBuffer.size)
        System.arraycopy(t2PhysicalMain, skipBytes, expectedT2, overreadBuffer.size, t2PhysicalMain.size - skipBytes)
        System.arraycopy(t2PhysicalOvershoot, 0, expectedT2, trackBytesSize - skipBytes, skipBytes)

        val verifier = AccurateRipVerifier()

        val expectedChecksum1 = verifier.computeChecksumChunk(
            expectedT1, 1, totalSamples,
            isFirstTrack = true, isLastTrack = false
        ).partialChecksum

        val expectedChecksum2 = verifier.computeChecksumChunk(
            expectedT2, 1, totalSamples,
            isFirstTrack = false, isLastTrack = true
        ).partialChecksum

        // Simulate RipManager Accumulator feeding
        val accumulatorT1 = ChecksumAccumulator(verifier, totalSamples, isFirstTrack = true, isLastTrack = false)
        // track 1:
        accumulatorT1.accumulate(t1PhysicalMain.copyOfRange(skipBytes, t1PhysicalMain.size))
        accumulatorT1.accumulate(t1PhysicalOvershoot.copyOfRange(0, skipBytes))
        val t1Actual = accumulatorT1.ripChecksum

        assertEquals("Track 1 checksum mismatch", expectedChecksum1, t1Actual)

        val accumulatorT2 = ChecksumAccumulator(verifier, totalSamples, isFirstTrack = false, isLastTrack = true)
        // track 2:
        accumulatorT2.accumulate(overreadBuffer)
        accumulatorT2.accumulate(t2PhysicalMain.copyOfRange(skipBytes, t2PhysicalMain.size))
        accumulatorT2.accumulate(t2PhysicalOvershoot.copyOfRange(0, skipBytes))
        val t2Actual = accumulatorT2.ripChecksum

        assertEquals("Track 2 checksum mismatch", expectedChecksum2, t2Actual)
    }

    @Test
    fun `cdparanoia model with negative offset`() {
        // -48 offset -> tocOffset = -1, sampleOffset = 540, skipBytes = 2160
        val sampleOffset = 540
        val skipBytes = 2160

        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        // Track 1
        val t1PhysicalMain = ByteArray(trackBytesSize) { (it % 256).toByte() }
        val t1PhysicalOvershoot = ByteArray(2352) { ((it + 10) % 256).toByte() }

        // Track 2
        val t2PhysicalMain = ByteArray(trackBytesSize - 2352) { ((it + 20) % 256).toByte() }
        val t2PhysicalOvershoot = ByteArray(2352) { ((it + 30) % 256).toByte() }

        val expectedT1 = ByteArray(trackBytesSize)
        System.arraycopy(t1PhysicalMain, skipBytes, expectedT1, 0, trackBytesSize - skipBytes)
        System.arraycopy(t1PhysicalOvershoot, 0, expectedT1, trackBytesSize - skipBytes, skipBytes)

        val expectedT2 = ByteArray(trackBytesSize)
        val overreadBuffer = t1PhysicalOvershoot.copyOfRange(skipBytes, 2352)
        System.arraycopy(overreadBuffer, 0, expectedT2, 0, overreadBuffer.size)
        System.arraycopy(t2PhysicalMain, skipBytes, expectedT2, overreadBuffer.size, t2PhysicalMain.size - skipBytes)
        System.arraycopy(t2PhysicalOvershoot, 0, expectedT2, trackBytesSize - skipBytes, skipBytes)

        val verifier = AccurateRipVerifier()

        val expectedChecksum1 = verifier.computeChecksumChunk(
            expectedT1, 1, totalSamples,
            isFirstTrack = true, isLastTrack = false
        ).partialChecksum

        val expectedChecksum2 = verifier.computeChecksumChunk(
            expectedT2, 1, totalSamples,
            isFirstTrack = false, isLastTrack = true
        ).partialChecksum

        val accumulatorT1 = ChecksumAccumulator(verifier, totalSamples, isFirstTrack = true, isLastTrack = false)
        accumulatorT1.accumulate(t1PhysicalMain.copyOfRange(skipBytes, t1PhysicalMain.size))
        accumulatorT1.accumulate(t1PhysicalOvershoot.copyOfRange(0, skipBytes))
        assertEquals("Track 1 checksum mismatch", expectedChecksum1, accumulatorT1.ripChecksum)

        val accumulatorT2 = ChecksumAccumulator(verifier, totalSamples, isFirstTrack = false, isLastTrack = true)
        accumulatorT2.accumulate(overreadBuffer)
        accumulatorT2.accumulate(t2PhysicalMain.copyOfRange(skipBytes, t2PhysicalMain.size))
        accumulatorT2.accumulate(t2PhysicalOvershoot.copyOfRange(0, skipBytes))
        assertEquals("Track 2 checksum mismatch", expectedChecksum2, accumulatorT2.ripChecksum)
    }

    @Test
    fun `exact multiple of 588 offset has no sampleOffset or overread`() {
        val driveOffset = 588
        // tocOffset = 1, sampleOffset = 0, skipBytes = 0
        val skipBytes = 0

        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        val t1PhysicalMain = ByteArray(trackBytesSize) { (it % 256).toByte() }

        // Expected T1 is just t1PhysicalMain
        val expectedT1 = t1PhysicalMain

        val verifier = AccurateRipVerifier()
        val expectedChecksum1 = verifier.computeChecksumChunk(
            expectedT1, 1, totalSamples,
            isFirstTrack = true, isLastTrack = true
        ).partialChecksum

        val accumulatorT1 = ChecksumAccumulator(verifier, totalSamples, isFirstTrack = true, isLastTrack = true)
        // No overread buffer, no skipBytes
        accumulatorT1.accumulate(t1PhysicalMain)

        assertEquals("Exact multiple checksum mismatch", expectedChecksum1, accumulatorT1.ripChecksum)
    }
}
