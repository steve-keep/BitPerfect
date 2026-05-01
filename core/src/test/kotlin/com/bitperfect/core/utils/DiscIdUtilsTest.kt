package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscIdUtilsTest {

    @Test
    fun computeMusicBrainzDiscId_structuralValidityAndDeterminism() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 16239),
            TocEntry(trackNumber = 3, lba = 29113),
            TocEntry(trackNumber = 4, lba = 46438),
            TocEntry(trackNumber = 5, lba = 53085),
            TocEntry(trackNumber = 6, lba = 64980),
            TocEntry(trackNumber = 7, lba = 77270),
            TocEntry(trackNumber = 8, lba = 95745),
            TocEntry(trackNumber = 9, lba = 108270),
            TocEntry(trackNumber = 10, lba = 122250)
        )
        val toc = DiscToc(
            tracks = tracks,
            leadOutLba = 247073
        )

        val discId1 = computeMusicBrainzDiscId(toc)
        val discId2 = computeMusicBrainzDiscId(toc)

        // Determinism
        org.junit.Assert.assertEquals(discId1, discId2)

        // Structure: 28 characters
        org.junit.Assert.assertEquals(28, discId1.length)

        // Structure: ends with -
        org.junit.Assert.assertTrue(discId1.endsWith("-"))

        // Structure: valid characters only (a-z, A-Z, 0-9, ., _, -)
        val validCharsRegex = Regex("^[a-zA-Z0-9._-]+$")
        org.junit.Assert.assertTrue(discId1.matches(validCharsRegex))

        // Ensure no +, /, or =
        org.junit.Assert.assertFalse(discId1.contains("+"))
        org.junit.Assert.assertFalse(discId1.contains("/"))
        org.junit.Assert.assertFalse(discId1.contains("="))
    }

    @Test
    fun computeAccurateRipDiscId_appetiteForDestruction_printsIds() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 18051),
            TocEntry(trackNumber = 3, lba = 32000),
            TocEntry(trackNumber = 4, lba = 47950),
            TocEntry(trackNumber = 5, lba = 63263),
            TocEntry(trackNumber = 6, lba = 80463),
            TocEntry(trackNumber = 7, lba = 95823),
            TocEntry(trackNumber = 8, lba = 112375),
            TocEntry(trackNumber = 9, lba = 130020),
            TocEntry(trackNumber = 10, lba = 149610),
            TocEntry(trackNumber = 11, lba = 165862),
            TocEntry(trackNumber = 12, lba = 181855)
        )
        val toc = DiscToc(
            tracks = tracks,
            leadOutLba = 197218
        )

        val ids = computeAccurateRipDiscId(toc)

        println("Appetite for Destruction AccurateRip IDs:")
        println("id1: ${String.format("%08x", ids.id1)}")
        println("id2: ${String.format("%08x", ids.id2)}")
        println("id3: ${String.format("%08x", ids.id3)}")
        // We will assert these values once they are verified against AccurateRip
    }
}
