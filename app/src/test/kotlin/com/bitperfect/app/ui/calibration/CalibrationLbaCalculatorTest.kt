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
    fun `sectorsToRead shrinks when clamped to account for skipped sector`() {
        val totalSectors = 13901
        val overshoot = 7
        // rawFirstLba = 150 - 150 = 0 -> clamped to 1 (difference = 1)
        val (_, sectorsToRead) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150,
            totalSectors = totalSectors,
            overshootSectors = overshoot
        )
        // Expected: 13901 + 7 - 1 = 13907
        assertEquals(totalSectors + overshoot - 1, sectorsToRead)
    }

    @Test
    fun `default overshoot of 7 provides more than 3000 samples of headroom even when clamped`() {
        // 6 sectors * 588 samples/sector = 3528 samples > 3000 (the scan range)
        // (clamped from 7 to 6)
        val (_, sectorsToRead) = calibrationLbaRange(
            trackLba = 150, pregapOffset = 150, totalSectors = 13901
        )
        val totalSectors = 13901
        // even though it's clamped, the effective read sector count is reduced by 1
        // however the total sectors includes the clamped sector conceptually.
        // What we want to test is sectorsToRead - (totalSectors - 1)
        val effectiveTrackSectors = totalSectors - 1
        val overshootSamples = (sectorsToRead - effectiveTrackSectors) * 588
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
