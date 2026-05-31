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

    @Test
    fun `cdExtra_lastTrack_usesAudioLeadOut`() {
        // Based on Ben Howard - Every Kingdom
        val audioLeadOutLba = 225673
        val leadOutLba = 247632
        val trackLba = 196867

        // We simulate the call using effectiveAudioLeadOutLba directly as nextLba
        val effectiveLeadOut = audioLeadOutLba // audioLeadOutLba ?: leadOutLba

        val (first, last) = ripLbaRange(
            trackLba = trackLba,
            nextLba = effectiveLeadOut,
            tocOffset = 0,
            pregapOffset = 150,
            isLastTrack = true
        )

        val expectedSectors = 28806 // 225673 - 196867
        val actualSectors = last - first + 1

        assertEquals(expectedSectors, actualSectors)
        assertEquals(trackLba - 150, first) // 196867 - 150 = 196717
        assertEquals(196717 + 28806 - 1, last)

        assertTrue(last < leadOutLba - 150)
    }

    // ── LBA 0 guard ─────────────────────────────────────────────────────────────

    @Test
    fun `track1 with pregapOffset=150 and tocOffset=0 - firstLba is clamped to 1 not 0`() {
        // Standard disc: Track 1 lba=150 (normalised), pregapOffset=150, no drive offset
        // rawFirstLba = 150 + 0 - 150 = 0 → must be clamped to 1
        val (first, _) = ripLbaRange(
            trackLba = 150, nextLba = 14051,
            tocOffset = 0, pregapOffset = 150,
            isLastTrack = false
        )
        assertEquals("firstLba must be clamped away from LBA 0", 1, first)
    }

    @Test
    fun `track1 LBA clamp adjusts sector count so lastLba is unchanged relative to raw calculation`() {
        // Without clamp: firstLba=0, lastLba=0+13901-1=13900
        // With clamp:    firstLba=1, lastLba=1+13901-1-1=13900  (same lastLba)
        val (first, last) = ripLbaRange(
            trackLba = 150, nextLba = 14051,
            tocOffset = 0, pregapOffset = 150,
            isLastTrack = false
        )
        assertEquals(1, first)
        assertEquals(13900, last)
    }

    @Test
    fun `track1 with tocOffset=1 - firstLba is 1 not 0 (tocOffset pushes past clamp)`() {
        // lbaStart = 150+1=151, rawFirstLba = 151-150 = 1 → no clamping needed
        val (first, _) = ripLbaRange(
            trackLba = 150, nextLba = 14051,
            tocOffset = 1, pregapOffset = 150,
            isLastTrack = false
        )
        assertEquals(1, first)
    }

    @Test
    fun `pregapOffset=0 disc - firstLba is trackLba (no clamping needed)`() {
        // 0-based disc not normalised: trackLba=0, pregapOffset=0
        // firstLba = 0+0-0 = 0 → clamped to 1
        val (first, _) = ripLbaRange(
            trackLba = 0, nextLba = 13901,
            tocOffset = 0, pregapOffset = 0,
            isLastTrack = false
        )
        assertEquals(1, first)
    }
}
