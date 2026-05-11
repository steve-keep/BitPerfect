package com.bitperfect.app.ui.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationLbaCalculatorTest {

    @Test
    fun `firstNativeLba is never 0`() {
        // LBA 0 is unreadable on most drives — must always start at >= 1
        val (firstLba, _) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150, totalSectors = 22644
        )
        assertTrue("firstNativeLba must be >= 1, was $firstLba", firstLba >= 1)
    }

    @Test
    fun `firstNativeLba is 1 for standard disc`() {
        // Standard disc: track1.lba=150, pregapOffset=150 → native LBA = 150-150+1 = 1
        val (firstLba, _) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150, totalSectors = 22644
        )
        assertEquals(1, firstLba)
    }

    @Test
    fun `firstNativeLba is 1 for zero-based TOC disc`() {
        // Some drives return 0-based LBAs (pregapOffset=0, track1.lba=0)
        // native LBA = 0 - 0 + 1 = 1
        val (firstLba, _) = calibrationLbaRange(
            trackLba = 0, pregapOffset = 0, totalSectors = 13901
        )
        assertEquals(1, firstLba)
    }

    @Test
    fun `sectorsToRead equals totalSectors plus overshoot`() {
        val totalSectors = 13901
        val overshoot = 7
        val (_, sectorsToRead) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150,
            totalSectors = totalSectors,
            overshootSectors = overshoot
        )
        assertEquals(totalSectors + overshoot, sectorsToRead)
    }

    @Test
    fun `default overshoot of 7 provides more than 3000 samples of headroom`() {
        // 7 sectors * 588 samples/sector = 4116 samples > 3000 (the scan range)
        val (_, sectorsToRead) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150, totalSectors = 13901
        )
        val totalSectors = 13901
        val overshootSamples = (sectorsToRead - totalSectors) * 588
        assertTrue(
            "Overshoot ($overshootSamples samples) must exceed scan range (3000 samples)",
            overshootSamples > 3000
        )
    }

    @Test
    fun `old buggy value LBA 0 is never returned`() {
        // Regression guard: the pre-fix formula was trackLba - pregapOffset = 0
        // This test will fail if the +1 is removed
        val (firstLba, _) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150, totalSectors = 13901
        )
        assertTrue(
            "LBA 0 is unreadable — regression of the missing +1 fix",
            firstLba != 0
        )
    }
}
