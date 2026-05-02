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
    fun computeAccurateRipDiscId_nevermindRemaster_calculatesCorrectly() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 22794),
            TocEntry(trackNumber = 3, lba = 41925),
            TocEntry(trackNumber = 4, lba = 58344),
            TocEntry(trackNumber = 5, lba = 72147),
            TocEntry(trackNumber = 6, lba = 91426),
            TocEntry(trackNumber = 7, lba = 104705),
            TocEntry(trackNumber = 8, lba = 115426),
            TocEntry(trackNumber = 9, lba = 132217),
            TocEntry(trackNumber = 10, lba = 143984),
            TocEntry(trackNumber = 11, lba = 159920),
            TocEntry(trackNumber = 12, lba = 174651)
        )
        val toc = DiscToc(
            tracks = tracks,
            leadOutLba = 267269
        )

        val ids = computeAccurateRipDiscId(toc)

        org.junit.Assert.assertEquals("00151a60", String.format("%08x", ids.id1))
        org.junit.Assert.assertEquals("00c51580", String.format("%08x", ids.id2))
        org.junit.Assert.assertEquals("ad0de90c", String.format("%08x", ids.id3))

        val url = ids.toUrl(toc.trackCount)
        org.junit.Assert.assertEquals("http://www.accuraterip.com/accuraterip/0/6/a/dBAR-012-00151a60-00c51580-ad0de90c.bin", url)
    }
}
