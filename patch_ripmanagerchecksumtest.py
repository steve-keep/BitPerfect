content = """package com.bitperfect.app.usb

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
    fun `cdparanoia model with positive offset`() {
        // Offset +667 -> tocOffset = 1, sampleOffset = 79, skipBytes = 316
        val driveOffset = 667
        val tocOffset = 1
        val sampleOffset = 79
        val skipBytes = 316

        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        // Let's create dummy physical bytes representing the "shifted" reads.
        // For track 1 main read (size: trackBytesSize)
        val t1PhysicalMain = ByteArray(trackBytesSize) { (it % 256).toByte() }
        // For track 1 overshoot sector (size: 2352)
        val t1PhysicalOvershoot = ByteArray(2352) { ((it + 10) % 256).toByte() }

        // For track 2 main read
        val t2PhysicalMain = ByteArray(trackBytesSize) { ((it + 20) % 256).toByte() }
        // For track 2 overshoot sector
        val t2PhysicalOvershoot = ByteArray(2352) { ((it + 30) % 256).toByte() }

        // Determine what the continuous Corrected PCM should be for each track.
        // Track 1: First `skipBytes` of t1PhysicalMain are dropped.
        // The rest of t1PhysicalMain is used.
        // Then the first `skipBytes` of t1PhysicalOvershoot is appended to complete the track.
        val expectedT1 = ByteArray(trackBytesSize)
        System.arraycopy(t1PhysicalMain, skipBytes, expectedT1, 0, trackBytesSize - skipBytes)
        System.arraycopy(t1PhysicalOvershoot, 0, expectedT1, trackBytesSize - skipBytes, skipBytes)

        // Track 2: First it receives the remainder of t1PhysicalOvershoot (the overreadBuffer).
        // Size of overreadBuffer = 2352 - skipBytes
        // Then it receives the main read of track 2.
        // Since overreadBuffer is used, the main read of track 2 starts at the sector AFTER the overshoot sector.
        // However, we don't drop `skipBytes` from the first sector of track 2 main read,
        // wait, we DO drop `skipBytes` only on the FIRST sector of the track? No, if overreadBuffer is used,
        // in RipManager, we do drop `skipBytes` on the first chunk of the main loop.
        // Wait, let's look at the instructions!
        // "On the very first sector of each track's main read loop, skip the leading sampleOffset * 4 bytes...
        // This only applies to the first sector of the track"
        // And "If overreadBuffer is non-null, feed it to encoder... Then run the main sector loop...
        // On the first sector of the main loop, slice off the leading skipBytes before encoding."

        // Wait, cdparanoia's sub-sector shift means that every track's data is shifted.
        // The overread buffer provides 2352 - skipBytes.
        // The first sector of the next track's main loop provides 2352 bytes. But its first `skipBytes` overlap with the end of the previous track theoretically?
        // Actually, cdparanoia drops `skipBytes` from the first sector of EVERY track.
        // Let's verify our logic in expectedT2:
        val overreadBuffer = t1PhysicalOvershoot.copyOfRange(skipBytes, 2352)
        val expectedT2 = ByteArray(trackBytesSize)
        // copy overreadBuffer to expectedT2
        System.arraycopy(overreadBuffer, 0, expectedT2, 0, overreadBuffer.size)
        // copy t2PhysicalMain minus `skipBytes` to the rest
        // But t2PhysicalMain is trackBytesSize. We drop `skipBytes`.
        // So t2PhysicalMain provides trackBytesSize - skipBytes.
        // Total bytes = (2352 - skipBytes) + (trackBytesSize - skipBytes) = trackBytesSize + 2352 - 2 * skipBytes.
        // That doesn't sum to trackBytesSize! We need track 2 to also overshoot!
        // To complete track 2, we take `skipBytes` from t2PhysicalOvershoot.
        // So: overreadBuffer + (t2PhysicalMain minus skipBytes) + first skipBytes of t2PhysicalOvershoot.
        // Total = (2352 - skipBytes) + (trackBytesSize - skipBytes) + skipBytes
        //       = trackBytesSize + 2352 - skipBytes. Wait!
        // A track is exactly `trackBytesSize` long.
        // Ah! If we consume `overreadBuffer` (1 sector minus skipBytes), then the main loop only needs to read `totalSectors - 1` sectors?
        // No, the main loop reads `totalSectors` sectors.
        // If main loop reads `totalSectors`, and we have `overreadBuffer`, we have too much data!
        // Let's look closely at cdparanoia's two-part decomposition in the prompt.
        pass
"""

with open("generate.py", "w") as f:
    f.write("print('ok')")
