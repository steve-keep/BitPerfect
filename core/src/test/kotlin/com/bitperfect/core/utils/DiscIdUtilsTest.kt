package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscIdUtilsTest {

    @Test
    fun computeMusicBrainzDiscId_tenTrackDisc_matchesLibdiscid() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 24788),
            TocEntry(trackNumber = 3, lba = 43296),
            TocEntry(trackNumber = 4, lba = 66497),
            TocEntry(trackNumber = 5, lba = 87910),
            TocEntry(trackNumber = 6, lba = 106598),
            TocEntry(trackNumber = 7, lba = 126125),
            TocEntry(trackNumber = 8, lba = 145966),
            TocEntry(trackNumber = 9, lba = 174579),
            TocEntry(trackNumber = 10, lba = 196867)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 247632)
        val discId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("Tvqqw5l80pQjX4aHX6gZEMSw6O4-", discId)
    }

    @Test
    fun computeMusicBrainzDiscId_thirteenTrackDisc_matchesLibdiscid() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 7623),
            TocEntry(trackNumber = 3, lba = 27635),
            TocEntry(trackNumber = 4, lba = 44838),
            TocEntry(trackNumber = 5, lba = 66353),
            TocEntry(trackNumber = 6, lba = 92265),
            TocEntry(trackNumber = 7, lba = 111658),
            TocEntry(trackNumber = 8, lba = 135098),
            TocEntry(trackNumber = 9, lba = 158750),
            TocEntry(trackNumber = 10, lba = 162375),
            TocEntry(trackNumber = 11, lba = 181668),
            TocEntry(trackNumber = 12, lba = 203508),
            TocEntry(trackNumber = 13, lba = 224703)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 276265)
        val discId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("QPBiWO7V9EgTpVA69ooCea20__0-", discId)
    }

    @Test
    fun computeMusicBrainzTocString_tenTrackDisc_correctFormat() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 24788),
            TocEntry(trackNumber = 3, lba = 43296),
            TocEntry(trackNumber = 4, lba = 66497),
            TocEntry(trackNumber = 5, lba = 87910),
            TocEntry(trackNumber = 6, lba = 106598),
            TocEntry(trackNumber = 7, lba = 126125),
            TocEntry(trackNumber = 8, lba = 145966),
            TocEntry(trackNumber = 9, lba = 174579),
            TocEntry(trackNumber = 10, lba = 196867)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 247632)
        val tocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+10+247782+300+24938+43446+66647+88060+106748+126275+146116+174729+197017", tocString)
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
