package com.bitperfect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import com.bitperfect.core.utils.SettingsManager

class AccurateRipServiceTest {

    @Test
    fun accurateRipURLIsBuiltCorrectlyFromMockDisc() {
        val disc = SettingsManager.NEVERMIND_MOCK
        val service = AccurateRipService()

        val discId = AccurateRipDiscId(
            id1 = disc.accurateRipId1!!,
            id2 = disc.accurateRipId2!!,
            id3 = disc.cddbId!!.toUInt()
        )

        val urlName = service.generateAccurateRipUrlName(disc.tracks.size, discId)
        assertEquals("dBAR-012-0034e486-002dc40c-ad0b0c0c.bin", urlName)

        val id1Hex = "%08x".format(discId.id1.toLong())
        val x = id1Hex[id1Hex.length - 1]
        val y = id1Hex[id1Hex.length - 2]
        val z = id1Hex[id1Hex.length - 3]
        val urlPath = "accuraterip/${x}/${y}/${z}/${urlName}"

        assertEquals("accuraterip/6/8/4/dBAR-012-0034e486-002dc40c-ad0b0c0c.bin", urlPath)
    }

    @Test
    fun accurateRipCRCV1MatchesForAllTracks() {
        val disc = SettingsManager.NEVERMIND_MOCK
        val expectedCrcs = disc.trackCrcsV1!!

        assertEquals(12, expectedCrcs.size)
        assertEquals(0x4F3E8B2A.toInt(), expectedCrcs[0])
    }
}
