package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapVerifierTest {
    @Test
    fun testExtractOverlapHeadAndTail() {
        val verifier = OverlapVerifier(overlapSizeSectors = 1)
        val sectorSize = 2352

        // A chunk of 2 sectors
        val chunk = ByteArray(sectorSize * 2) { it.toByte() }

        val head = verifier.extractOverlapHead(chunk)
        assertEquals(sectorSize, head.size)
        assertArrayEquals(chunk.copyOfRange(0, sectorSize), head)

        val tail = verifier.extractOverlapTail(chunk)
        assertEquals(sectorSize, tail.size)
        assertArrayEquals(chunk.copyOfRange(sectorSize, sectorSize * 2), tail)
    }

    @Test
    fun testVerifyOverlap() {
        val verifier = OverlapVerifier(overlapSizeSectors = 1)

        val tail = ByteArray(2352) { it.toByte() }
        val headMatch = ByteArray(2352) { it.toByte() }
        val headMismatch = ByteArray(2352) { (it + 1).toByte() }

        assertTrue(verifier.verifyOverlap(tail, headMatch))
        assertFalse(verifier.verifyOverlap(tail, headMismatch))
    }
}
