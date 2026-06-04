package com.bitperfect.app.usb

import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.AccurateRipDiscPressing
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
        val totalSamples = 20L * 588L
        val accumulator = ChecksumAccumulator(totalSamples)

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
        val accumulatorT1 = ChecksumAccumulator(totalSamples, isFirstTrack = true, isLastTrack = false)
        // track 1:
        accumulatorT1.accumulate(t1PhysicalMain.copyOfRange(skipBytes, t1PhysicalMain.size))
        accumulatorT1.accumulate(t1PhysicalOvershoot.copyOfRange(0, skipBytes))
        val t1Actual = accumulatorT1.ripChecksumV1

        assertEquals("Track 1 checksum mismatch", expectedChecksum1, t1Actual)

        val accumulatorT2 = ChecksumAccumulator(totalSamples, isFirstTrack = false, isLastTrack = true)
        // track 2:
        accumulatorT2.accumulate(overreadBuffer)
        accumulatorT2.accumulate(t2PhysicalMain.copyOfRange(skipBytes, t2PhysicalMain.size))
        accumulatorT2.accumulate(t2PhysicalOvershoot.copyOfRange(0, skipBytes))
        val t2Actual = accumulatorT2.ripChecksumV1

        assertEquals("Track 2 checksum mismatch", expectedChecksum2, t2Actual)
    }

    @Test
    fun `cdparanoia model with negative offset and LBA 0 clamping`() {
        // LBA 0 is unreadable on typical drives. Clamping to LBA 1 no longer reduces
        // effectiveTotalSectors, so no silence padding occurs.
        // -48 offset -> tocOffset = -1, sampleOffset = 540, skipBytes = 2160
        // Because of -1 tocOffset, track 1 starts at LBA 0 (before pregap normalisation for simplicity,
        // or pregap offset causes it). We will simulate clamping here.
        val tocOffset = -1
        val sampleOffset = 540
        val skipBytes = 2160

        val sectorsPerTrack = 20
        val trackBytesSize = sectorsPerTrack * 2352
        val totalSamples = sectorsPerTrack * 588L

        // Track 1
        // LBA 0 clamp does not reduce effectiveTotalSectors, so no missingStartSectors padding.
        val missingStartSectors = 0

        // Physical read will return full 20 sectors
        val t1PhysicalMain = ByteArray(sectorsPerTrack * 2352) { (it % 256).toByte() }
        val t1PhysicalOvershoot = ByteArray(2352) { ((it + 10) % 256).toByte() }

        // Construct expected continuous PCM stream:
        // 1. Physical sectors (trimmed by skipBytes)
        // 2. Overshoot (skipBytes amount)
        val expectedT1 = ByteArray(trackBytesSize)
        System.arraycopy(t1PhysicalMain, skipBytes, expectedT1, 0, trackBytesSize - skipBytes)
        System.arraycopy(t1PhysicalOvershoot, 0, expectedT1, trackBytesSize - skipBytes, skipBytes)

        // Track 2
        val t2PhysicalMain = ByteArray(trackBytesSize - 2352) { ((it + 20) % 256).toByte() }
        val t2PhysicalOvershoot = ByteArray(2352) { ((it + 30) % 256).toByte() }

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

        val accumulatorT1 = ChecksumAccumulator(totalSamples, isFirstTrack = true, isLastTrack = false)
        // Feed physical reads (trimmed by skipBytes)
        accumulatorT1.accumulate(t1PhysicalMain.copyOfRange(skipBytes, t1PhysicalMain.size))
        accumulatorT1.accumulate(t1PhysicalOvershoot.copyOfRange(0, skipBytes))
        assertEquals("Track 1 checksum mismatch", expectedChecksum1, accumulatorT1.ripChecksumV1)

        val accumulatorT2 = ChecksumAccumulator(totalSamples, isFirstTrack = false, isLastTrack = true)
        accumulatorT2.accumulate(overreadBuffer)
        accumulatorT2.accumulate(t2PhysicalMain.copyOfRange(skipBytes, t2PhysicalMain.size))
        accumulatorT2.accumulate(t2PhysicalOvershoot.copyOfRange(0, skipBytes))
        assertEquals("Track 2 checksum mismatch", expectedChecksum2, accumulatorT2.ripChecksumV1)
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

        val accumulatorT1 = ChecksumAccumulator(totalSamples, isFirstTrack = true, isLastTrack = true)
        // No overread buffer, no skipBytes
        accumulatorT1.accumulate(t1PhysicalMain)

        assertEquals("Exact multiple checksum mismatch", expectedChecksum1, accumulatorT1.ripChecksumV1)
    }

    @Test
    fun `verification match with V1 only pressing`() {
        val pressingA = com.bitperfect.core.services.AccurateRipDiscPressing(
            discId1 = 1L, discId2 = 1L,
            tracks = mapOf(1 to AccurateRipTrackMetadata(crcV1 = 0xAAAA, crcV2 = null, confidence = 5))
        )
        val expectedChecksums = listOf(pressingA)
        val activePressingCandidates = expectedChecksums.toMutableSet()

        val finalChecksumV1 = 0xAAAAL
        val finalChecksumV2 = 0xBBBBL // Does not match

        activePressingCandidates.retainAll { pressing ->
            val dbTrack = pressing.tracks[1] ?: return@retainAll false
            if (dbTrack.crcV2 != null) {
                dbTrack.crcV2 == finalChecksumV2
            } else {
                dbTrack.crcV1 == finalChecksumV1
            }
        }

        val matchedVersion = if (activePressingCandidates.isNotEmpty()) {
            if (activePressingCandidates.any { it.tracks[1]?.crcV2 == finalChecksumV2 }) 2 else 1
        } else null

        val allExpectedV1 = expectedChecksums.mapNotNull { it.tracks[1]?.crcV1 }.distinct()
        val allExpectedV2 = expectedChecksums.mapNotNull { it.tracks[1]?.crcV2 }.distinct()
        val hasExpected = allExpectedV1.isNotEmpty() || allExpectedV2.isNotEmpty()

        val finalStatus = if (activePressingCandidates.isNotEmpty()) {
            RipStatus.SUCCESS
        } else if (!hasExpected) {
            RipStatus.UNVERIFIED
        } else {
            RipStatus.WARNING
        }

        assertEquals(1, activePressingCandidates.size)
        assertEquals(1, matchedVersion)
        assertEquals(RipStatus.SUCCESS, finalStatus)
    }

    @Test
    fun `verification match with V2 preferred pressing`() {
        val pressingA = com.bitperfect.core.services.AccurateRipDiscPressing(
            discId1 = 1L, discId2 = 1L,
            tracks = mapOf(1 to AccurateRipTrackMetadata(crcV1 = 0xAAAA, crcV2 = 0xBBBB, confidence = 5))
        )
        val expectedChecksums = listOf(pressingA)
        val activePressingCandidates = expectedChecksums.toMutableSet()

        val finalChecksumV1 = 0xCCCCL // Does not match
        val finalChecksumV2 = 0xBBBBL // Matches V2

        activePressingCandidates.retainAll { pressing ->
            val dbTrack = pressing.tracks[1] ?: return@retainAll false
            if (dbTrack.crcV2 != null) {
                dbTrack.crcV2 == finalChecksumV2
            } else {
                dbTrack.crcV1 == finalChecksumV1
            }
        }

        val matchedVersion = if (activePressingCandidates.isNotEmpty()) {
            if (activePressingCandidates.any { it.tracks[1]?.crcV2 == finalChecksumV2 }) 2 else 1
        } else null

        val allExpectedV1 = expectedChecksums.mapNotNull { it.tracks[1]?.crcV1 }.distinct()
        val allExpectedV2 = expectedChecksums.mapNotNull { it.tracks[1]?.crcV2 }.distinct()
        val hasExpected = allExpectedV1.isNotEmpty() || allExpectedV2.isNotEmpty()

        val finalStatus = if (activePressingCandidates.isNotEmpty()) {
            RipStatus.SUCCESS
        } else if (!hasExpected) {
            RipStatus.UNVERIFIED
        } else {
            RipStatus.WARNING
        }

        assertEquals(1, activePressingCandidates.size)
        assertEquals(2, matchedVersion)
        assertEquals(RipStatus.SUCCESS, finalStatus)
    }

    @Test
    fun `matched pressing confidence is the maximum across surviving pressings`() {
        val pressingLow = AccurateRipDiscPressing(
            discId1 = 1L, discId2 = 1L,
            tracks = mapOf(1 to AccurateRipTrackMetadata(crcV1 = 0xAAAAL, crcV2 = null, confidence = 3))
        )
        val pressingHigh = AccurateRipDiscPressing(
            discId1 = 2L, discId2 = 2L,
            tracks = mapOf(1 to AccurateRipTrackMetadata(crcV1 = 0xAAAAL, crcV2 = null, confidence = 47))
        )
        val candidates = mutableSetOf(pressingLow, pressingHigh)
        // Both match the same checksum
        candidates.retainAll { it.tracks[1]?.crcV1 == 0xAAAAL }

        val confidence = candidates.mapNotNull { it.tracks[1]?.confidence }.maxOrNull()
        assertEquals(47, confidence)
    }
}
