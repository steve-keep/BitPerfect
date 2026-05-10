package com.bitperfect.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RipLbaCalculatorTest {

    // ── non-last track ──────────────────────────────────────────────────────

    @Test
    fun `non-last track - lba range starts at trackLba plus tocOffset`() {
        val (first, _) = ripLbaRange(
            trackLba = 150, nextLba = 14051,
            tocOffset = 1, pregapOffset = 150,
            isLastTrack = false
        )
        // lbaStart = 150 + 1 = 151; firstLba = 151 - 150 = 1
        assertEquals(1, first)
    }

    @Test
    fun `non-last track - last lba is one sector before next track start (no reduction)`() {
        val (_, last) = ripLbaRange(
            trackLba = 150, nextLba = 14051,
            tocOffset = 1, pregapOffset = 150,
            isLastTrack = false
        )
        // totalSectors = 14051 - 150 = 13901
        // effectiveTotalSectors = 13901 (no reduction for non-last)
        // lastLba = 1 + 13901 - 1 = 13901
        assertEquals(13901, last)
    }

    // ── last track (the bug) ────────────────────────────────────────────────

    @Test
    fun `last track - last lba does not reach lead-out`() {
        val leadOutLba = 182471
        val track16Lba = 172621
        val (_, last) = ripLbaRange(
            trackLba = track16Lba, nextLba = leadOutLba,
            tocOffset = 1, pregapOffset = 150,
            isLastTrack = true
        )
        assertTrue(
            "lastLba $last must be strictly less than leadOutLba $leadOutLba",
            last < leadOutLba - 150   // pregap-adjusted lead-out
        )
    }

    @Test
    fun `last track with tocOffset=1 - last lba is 1 sector before adjusted lead-out`() {
        // Disc: track 16 LBA=172621, leadOut=182471, pregapOffset=150, tocOffset=1
        // lbaStart = 172621 + 1 = 172622
        // totalSectors = 182471 - 172621 = 9850
        // effectiveTotalSectors = 9850 - 1 = 9849  (tocOffset subtracted)
        // firstLba = 172622 - 150 = 172472
        // lastLba  = 172472 + 9849 - 1 = 182320
        // pregap-adjusted lead-out = 182471 - 150 = 182321
        // 182320 < 182321 ✓
        val (first, last) = ripLbaRange(
            trackLba = 172621, nextLba = 182471,
            tocOffset = 1, pregapOffset = 150,
            isLastTrack = true
        )
        assertEquals(172472, first)
        assertEquals(182320, last)
    }

    @Test
    fun `last track with tocOffset=0 - range is unchanged`() {
        // driveOffset=0: no shifting, no reduction needed
        val (first, last) = ripLbaRange(
            trackLba = 172621, nextLba = 182471,
            tocOffset = 0, pregapOffset = 150,
            isLastTrack = true
        )
        assertEquals(172471, first)   // 172621 - 150
        assertEquals(182320, last)    // 172471 + 9850 - 1
    }

    @Test
    fun `last track with tocOffset=2 - reduces by 2 sectors`() {
        // driveOffset=1176 → tocOffset=2
        val (first, last) = ripLbaRange(
            trackLba = 172621, nextLba = 182471,
            tocOffset = 2, pregapOffset = 150,
            isLastTrack = true
        )
        val totalSectors = 182471 - 172621       // 9850
        val effectiveSectors = totalSectors - 2  // 9848
        val expectedFirst = 172621 + 2 - 150     // 172473
        val expectedLast  = expectedFirst + effectiveSectors - 1
        assertEquals(expectedFirst, first)
        assertEquals(expectedLast, last)
        assertTrue(last < 182471 - 150)
    }
}