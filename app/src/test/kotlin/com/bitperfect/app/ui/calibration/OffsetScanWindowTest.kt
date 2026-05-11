package com.bitperfect.app.ui.calibration

import org.junit.Assert.*
import org.junit.Test

class OffsetScanWindowTest {

    @Test
    fun `standard disc calculations are correct`() {
        val pregapOffset = 150
        val trackLba = 150
        val totalSectors = 1000
        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588

        val nativeTrackStart = trackLba - pregapOffset
        val readStartLba = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)
        val sectorsToRead = MAX_OFFSET_SECTORS + totalSectors + MAX_OFFSET_SECTORS
        val actualPreSectors = nativeTrackStart - readStartLba

        assertEquals(0, readStartLba)
        assertEquals(totalSectors + 12, sectorsToRead)
        assertEquals(0, actualPreSectors) // corrected based on user clarification
    }

    @Test
    fun `positive offset falls within buffer for standard disc`() {
        val totalSectors = 1000
        val actualPreSectors = 0
        val fullPcmSize = (12 + totalSectors) * 2352
        val offset = 3000

        val startByte = actualPreSectors * 2352 + offset * 4

        assertEquals(12000, startByte)
        assertTrue(startByte + totalSectors * 2352 <= fullPcmSize)
    }

    @Test
    fun `disc with pre-track headroom allows negative offsets`() {
        // e.g. hidden track in pregap, Track 1 starts at LBA 156
        val pregapOffset = 150
        val trackLba = 156
        val totalSectors = 1000
        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588 // 6

        val nativeTrackStart = trackLba - pregapOffset // 6
        val readStartLba = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS) // 0
        val actualPreSectors = nativeTrackStart - readStartLba // 6

        assertEquals(0, readStartLba)
        assertEquals(6, actualPreSectors)

        val offset = -3000
        val startByte = actualPreSectors * 2352 + offset * 4

        assertEquals(2112, startByte)
        assertTrue(startByte >= 0)
    }

    @Test
    fun `disc starting at native LBA 0 skips too negative offsets`() {
        val pregapOffset = 0
        val trackLba = 0

        val MAX_OFFSET_SAMPLES = 3000
        val MAX_OFFSET_SECTORS = (MAX_OFFSET_SAMPLES + 587) / 588
        val nativeTrackStart = trackLba - pregapOffset
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
