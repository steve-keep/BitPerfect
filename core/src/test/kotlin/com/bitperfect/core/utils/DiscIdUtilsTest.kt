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
        org.junit.Assert.assertEquals("KVH9hRP8qRxGDHlc6GRCFWGh_wo-", discId)
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
        org.junit.Assert.assertEquals("n9R9CLr6a9FBBTqB_Vsk9CYIhfQ-", discId)
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
        org.junit.Assert.assertEquals("1+10+247632+150+24788+43296+66497+87910+106598+126125+145966+174579+196867", tocString)
    }

    @Test
    fun debugReportFixture_calculatesAllIdsCorrectly() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 31198),
            TocEntry(trackNumber = 3, lba = 52868),
            TocEntry(trackNumber = 4, lba = 80115),
            TocEntry(trackNumber = 5, lba = 91290),
            TocEntry(trackNumber = 6, lba = 122980),
            TocEntry(trackNumber = 7, lba = 146928),
            TocEntry(trackNumber = 8, lba = 166415),
            TocEntry(trackNumber = 9, lba = 176298),
            TocEntry(trackNumber = 10, lba = 193385)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 233745)

        val mbId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("mW3Rj1CEhQgkx5LnlEpLZlnmEeQ-", mbId)

        val mbTocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+10+233745+150+31198+52868+80115+91290+122980+146928+166415+176298+193385", mbTocString)

        val arIds = computeAccurateRipDiscId(toc)
        org.junit.Assert.assertEquals("0013bd9a", String.format("%08x", arIds.id1))
        org.junit.Assert.assertEquals("009b4c30", String.format("%08x", arIds.id2))
    }

    @Test
    fun debugReportFixture_cdExtra_calculatesMusicBrainzIdsCorrectly() {
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
        // audio session leadout is 225673
        val toc = DiscToc(tracks = tracks, leadOutLba = 247632, audioLeadOutLba = 225673)

        val mbId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("Cr1Rf7_SmFh.v1QEjuASxozukf0-", mbId)

        val mbTocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+10+225673+150+24788+43296+66497+87910+106598+126125+145966+174579+196867", mbTocString)
    }

    @Test
    fun computeMusicBrainzDiscId_standardAudioCd_kyuss_fixtureA() {
        // Fixture A — Standard audio CD (Kyuss, Blues for the Red Sun)
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 31198),
            TocEntry(trackNumber = 3, lba = 52868),
            TocEntry(trackNumber = 4, lba = 80115),
            TocEntry(trackNumber = 5, lba = 91290),
            TocEntry(trackNumber = 6, lba = 122980),
            TocEntry(trackNumber = 7, lba = 146928),
            TocEntry(trackNumber = 8, lba = 166415),
            TocEntry(trackNumber = 9, lba = 176298),
            TocEntry(trackNumber = 10, lba = 193385)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 233745, audioLeadOutLba = null)

        val discId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("mW3Rj1CEhQgkx5LnlEpLZlnmEeQ-", discId)

        val tocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+10+233745+150+31198+52868+80115+91290+122980+146928+166415+176298+193385", tocString)
    }

    @Test
    fun computeMusicBrainzDiscId_cdExtra_benHoward_fixtureB() {
        // Fixture B — CD-Extra (Ben Howard, Every Kingdom)
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
        val toc = DiscToc(tracks = tracks, leadOutLba = 247632, audioLeadOutLba = 225673)

        val discId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertEquals("Cr1Rf7_SmFh.v1QEjuASxozukf0-", discId)

        val tocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+10+225673+150+24788+43296+66497+87910+106598+126125+145966+174579+196867", tocString)

        org.junit.Assert.assertNotEquals(
            computeMusicBrainzDiscId(
                DiscToc(tracks, leadOutLba = 247632, audioLeadOutLba = null)
            ),
            computeMusicBrainzDiscId(
                DiscToc(tracks, leadOutLba = 247632, audioLeadOutLba = 225673)
            )
        )
    }

    @Test
    fun computeMusicBrainzDiscId_cdExtra_synthetic_heuristic_fixtureC() {
        // Fixture C — CD-Extra, synthetic, heuristic path
        // For testing the DiscToc we just pass the values directly as the DiscToc should use audioLeadOutLba if present
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 50000),
            TocEntry(trackNumber = 3, lba = 100000)
        )
        // heuristic leadout: 180000 - 11250 = 168750
        val toc = DiscToc(tracks = tracks, leadOutLba = 210000, audioLeadOutLba = 168750)

        // Let's verify the tocstring and disc ID are using 168750
        val tocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+3+168750+150+50000+100000", tocString)

        // disc ID should be based on 168750
        val discId = computeMusicBrainzDiscId(toc)
        org.junit.Assert.assertNotEquals("Should not be null or empty", "", discId)
        // I won't hardcode a hash for the synthetic one since we just care it uses the right leadout,
        // but verifying the tocString is sufficient to prove the leadout preference
    }

    @Test
    fun computeMusicBrainzDiscId_singleSessionAudio_synthetic_fixtureD() {
        // Fixture D — Synthetic single-session audio CD
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 50000),
            TocEntry(trackNumber = 3, lba = 100000)
        )
        val toc = DiscToc(tracks = tracks, leadOutLba = 150000, audioLeadOutLba = null)

        val tocString = computeMusicBrainzTocString(toc)
        org.junit.Assert.assertEquals("1+3+150000+150+50000+100000", tocString)
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
