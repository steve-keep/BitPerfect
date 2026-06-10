package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipDiscPressing
import com.bitperfect.core.services.AccurateRipTrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyTrackTest {

    private fun mockRipManager() = RipManager(
        context = org.mockito.Mockito.mock(android.content.Context::class.java),
        outputFolderUriString = "",
        toc = com.bitperfect.core.models.DiscToc(emptyList(), 0, 0),
        metadata = com.bitperfect.core.models.DiscMetadata("", "", emptyList(), mbReleaseId = ""),
        expectedChecksums = emptyList(),
        artworkBytes = null,
        driveVendor = "",
        driveProduct = "",
        initialTracks = emptyList()
    )

    private fun makePressing(crcV1: Long?, crcV2: Long?, confidence: Int): AccurateRipDiscPressing {
        return AccurateRipDiscPressing(
            discId1 = 1L,
            discId2 = 2L,
            tracks = mapOf(1 to AccurateRipTrackMetadata(crcV1 ?: 0L, crcV2, confidence))
        )
    }

    @Test
    fun verifyTrack_v1Match_returnsSuccess() {
        val pressing = makePressing(crcV1 = 0xABCD1234L, crcV2 = null, confidence = 5)
        val expectedChecksums = listOf(pressing)
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0xABCD1234L,
            checksumV2 = 0L,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(RipStatus.SUCCESS, result.finalStatus)
        assertEquals(1, result.matchedVersion)
        assertEquals(5, result.matchedConfidence)
    }

    @Test
    fun verifyTrack_v2Match_returnsSuccessV2() {
        val pressing = makePressing(crcV1 = null, crcV2 = 0xDEADBEEFL, confidence = 10)
        val expectedChecksums = listOf(pressing)
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0L,
            checksumV2 = 0xDEADBEEFL,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(RipStatus.SUCCESS, result.finalStatus)
        assertEquals(2, result.matchedVersion)
    }

    @Test
    fun verifyTrack_mismatch_returnsWarning() {
        val pressing = makePressing(crcV1 = 0x11111111L, crcV2 = null, confidence = 5)
        val expectedChecksums = listOf(pressing)
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0x22222222L,
            checksumV2 = 0L,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(RipStatus.WARNING, result.finalStatus)
        assertNull(result.matchedVersion)
        assertEquals(listOf(0x11111111L), result.allExpectedV1)
    }

    @Test
    fun verifyTrack_noExpectedChecksums_returnsUnverified() {
        val expectedChecksums = emptyList<AccurateRipDiscPressing>()
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0L,
            checksumV2 = 0L,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(RipStatus.UNVERIFIED, result.finalStatus)
    }

    @Test
    fun verifyTrack_candidateElimination_mutatesSet() {
        val pressingA = makePressing(crcV1 = 0xABCD1234L, crcV2 = null, confidence = 5)
        val pressingB = makePressing(crcV1 = 0x11111111L, crcV2 = null, confidence = 10)
        val expectedChecksums = listOf(pressingA, pressingB)
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0xABCD1234L,
            checksumV2 = 0L,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(1, candidates.size)
        assertTrue(candidates.contains(pressingA))
    }

    @Test
    fun verifyTrack_multiplePressings_picksHighestConfidence() {
        val pressingA = makePressing(crcV1 = 0xABCD1234L, crcV2 = null, confidence = 12)
        val pressingB = makePressing(crcV1 = 0xABCD1234L, crcV2 = null, confidence = 35)
        val expectedChecksums = listOf(pressingA, pressingB)
        val candidates = expectedChecksums.toMutableSet()

        val ripManager = mockRipManager()
        val result = ripManager.verifyTrack(
            trackNumber = 1,
            checksumV1 = 0xABCD1234L,
            checksumV2 = 0L,
            activePressingCandidates = candidates,
            expectedChecksums = expectedChecksums
        )

        assertEquals(35, result.matchedConfidence)
    }
}
