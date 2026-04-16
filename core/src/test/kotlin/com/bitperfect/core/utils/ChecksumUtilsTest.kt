package com.bitperfect.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumUtilsTest {

    @Test
    fun testAccurateRipCrc() {
        // Mock sector data (all zeros)
        val sector = ByteArray(2352) { 0 }

        // Track 1, Sector 0: should be 0 due to skipping first 5 sectors
        assertEquals(0L, ChecksumUtils.calculateAccurateRipCrc(sector, 0, 100, true, false))

        // Track 1, Sector 4: special case
        // If sector is all zeros, result should be 0
        assertEquals(0L, ChecksumUtils.calculateAccurateRipCrc(sector, 4, 100, true, false))

        // Generic sector in the middle
        assertEquals(0L, ChecksumUtils.calculateAccurateRipCrc(sector, 10, 100, false, false))

        // Sector with some data
        val dataSector = ByteArray(2352) { 0 }
        // Set first sample to 1 (Little Endian: [1, 0, 0, 0])
        dataSector[0] = 1

        // Sector index 10 (not first/last)
        // framesPerSector = 588
        // frameOffset starts at 10 * 588 = 5880
        // First frame (i=0): sample = 1, frameOffset = 5881. Contribution = 1 * 5881 = 5881
        // Other frames: sample = 0.
        assertEquals(5881L, ChecksumUtils.calculateAccurateRipCrc(dataSector, 10, 100, false, false))
    }

    @Test
    fun testFreeDbId() {
        val offsets = longArrayOf(150, 15150) // Track 1 at 2s, Track 2 at 3m22s (mock)
        val leadOut = 30150L // 6m42s

        // Track 1: 150/75 = 2. Sum digits of 2 = 2.
        // Track 2: 15150/75 = 202. Sum digits of 202 = 2+0+2 = 4.
        // Sum = 2 + 4 = 6.
        // totalTime = (30150 - 150) / 75 = 30000 / 75 = 400.
        // sum % 0xFF = 6.
        // discId = (6 << 24) | (400 << 8) | 2
        val expected = (6L shl 24) or (400L shl 8) or 2L
        assertEquals(expected, MusicBrainzUtils.calculateFreeDbId(offsets, leadOut))
    }
}
