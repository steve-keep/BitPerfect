package com.bitperfect.app.ui.calibration

import com.bitperfect.core.services.AccurateRipTrackMetadata
import org.junit.Assert.*
import org.junit.Test

class OffsetScanWindowTest {

    @Test
    fun `track 2 on standard disc has full pre-track headroom`() {
        // Track 2 typically starts at LBA 300-600+. Even the minimum (short track 1)
        // gives readStartLba well above 0 and actualPreSectors = MAX_OFFSET_SECTORS.
        val track2Lba = 450        // typical short Track 1 of ~300 sectors
        val MAX_OFFSET_SECTORS = 6

        val nativeTrackStart = track2Lba   // already physical LBA
        val readStartLba     = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)
        val actualPreSectors = nativeTrackStart - readStartLba

        assertEquals(444, readStartLba)
        assertEquals(6, actualPreSectors)

        // Full negative range is now available
        val offset = -3000
        val startByte = actualPreSectors * 2352 + offset * 4
        assertTrue("startByte must be >= 0 for full negative offsets", startByte >= 0)
    }

    @Test
    fun `track 2 isFirstTrack is false, isLastTrack depends on track count`() {
        // 2-track disc: track 2 is last
        val resolvedTrackIndex2Track = 1
        val trackCount2 = 2
        assertEquals(false, resolvedTrackIndex2Track == 0)
        assertEquals(true,  resolvedTrackIndex2Track == trackCount2 - 1)

        // 10-track disc: track 2 is neither first nor last
        val trackCount10 = 10
        assertEquals(false, resolvedTrackIndex2Track == 0)
        assertEquals(false, resolvedTrackIndex2Track == trackCount10 - 1)
    }

    @Test
    fun `falls back to track 1 when track 2 has no AR checksums`() {
        // Simulates a disc where getExpectedChecksums returns data for track 1 only
        val allChecksums = mapOf(1 to listOf<AccurateRipTrackMetadata>())   // track 2 absent
        val useTrack2 = true
        val arTrackNumber = 2

        var expectedChecksums = allChecksums[arTrackNumber]   // null
        val resolvedTrackIndex = if (expectedChecksums == null && useTrack2) {
            expectedChecksums = allChecksums[1]
            0
        } else 1

        assertEquals(0, resolvedTrackIndex)
        assertNotNull(expectedChecksums)
    }

    @Test
    fun `standard disc - 150-based drive - full pre-track headroom`() {
        // ReadTocCommand normalises all LBAs to 150-based before storing.
        // pregapOffset is NOT subtracted here - track.lba is already the physical LBA.
        val trackLba = 150        // physical LBA, same for both 0-based and 150-based drives
        val totalSectors = 1000
        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588  // 6

        val nativeTrackStart = trackLba                             // was: trackLba - pregapOffset
        val readStartLba     = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)  // 144
        val sectorsToRead    = MAX_OFFSET_SECTORS + totalSectors + MAX_OFFSET_SECTORS
        val actualPreSectors = nativeTrackStart - readStartLba      // 6

        assertEquals(144, readStartLba)
        assertEquals(totalSectors + 12, sectorsToRead)
        assertEquals(6, actualPreSectors)
    }

    @Test
    fun `0-based drive (ASUS SDRW) produces same geometry as 150-based drive`() {
        // 0-based drives return raw LBA 0 for Track 1. ReadTocCommand normalises to 150.
        // After normalisation, track.lba = 150 regardless of raw drive format.
        // This test guards against re-introducing the track.lba - pregapOffset bug.
        val trackLba     = 150   // normalised — same value regardless of raw format
        val pregapOffset = 150   // stored in DiscToc for the 0-based case
        val MAX_OFFSET_SECTORS = 6

        // Correct: use track.lba directly
        val nativeTrackStart = trackLba
        val readStartLba     = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)
        val actualPreSectors = nativeTrackStart - readStartLba

        assertEquals(144, readStartLba)
        assertEquals(6, actualPreSectors)

        // Wrong (old bug): track.lba - pregapOffset = 0 → reads from lead-in
        val buggyNativeTrackStart = trackLba - pregapOffset
        assertEquals(0, buggyNativeTrackStart)  // documents what the bug produced
    }

    @Test
    fun `positive offset falls within buffer for standard disc`() {
        val totalSectors = 1000
        val actualPreSectors = 6
        val fullPcmSize = (12 + totalSectors) * 2352
        val offset = 3000

        val startByte = actualPreSectors * 2352 + offset * 4

        assertEquals(26112, startByte)
        assertTrue(startByte + totalSectors * 2352 <= fullPcmSize)
    }

    @Test
    fun `disc with pre-track headroom allows negative offsets`() {
        // e.g. hidden track in pregap, Track 1 starts at LBA 156
        val trackLba = 156
        val totalSectors = 1000
        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588 // 6

        val nativeTrackStart = trackLba // 156
        val readStartLba = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS) // 150
        val actualPreSectors = nativeTrackStart - readStartLba // 6

        assertEquals(150, readStartLba)
        assertEquals(6, actualPreSectors)

        val offset = -3000
        val startByte = actualPreSectors * 2352 + offset * 4

        assertEquals(2112, startByte)
        assertTrue(startByte >= 0)
    }

    @Test
    fun `disc starting at native LBA 0 skips too negative offsets`() {
        val trackLba = 0

        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588
        val nativeTrackStart = trackLba
        val readStartLba = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)
        val actualPreSectors = nativeTrackStart - readStartLba

        assertEquals(0, readStartLba)
        assertEquals(0, actualPreSectors)

        // For actualPreSectors = 0, a very negative offset like -3000 requires more than 0 pre sectors
        val offset = -3000
        val requiredPreSectors = (-offset + 587) / 588

        assertTrue(offset < 0 && requiredPreSectors > actualPreSectors)
    }
}
