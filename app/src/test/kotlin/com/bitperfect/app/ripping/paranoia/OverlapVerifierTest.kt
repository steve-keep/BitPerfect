package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlapVerifierTest {
    @Test
    fun testOverlapMatching() {
        val verifier = OverlapVerifier(overlapSizeSectors = 1, maxRereads = 3, trackNumber = 1)
        val sectorSize = 2352

        // Chunk 1: [0..1] (2 sectors)
        val chunk1 = ByteArray(sectorSize * 2) { it.toByte() }
        val committed1 = verifier.processChunk(chunk1, 0, 2, false) { _, _ -> null }
        assertNull(committed1)

        // Chunk 2 overlaps by 1 sector. It starts at sector 1. Length is 2 sectors.
        // It should match the tail of chunk1. The tail of chunk 1 is sector 1, which starts at `sectorSize`.
        val chunk2 = ByteArray(sectorSize * 2) { (it + sectorSize).toByte() }
        val committed2 = verifier.processChunk(chunk2, 1, 3, false) { _, _ -> null }

        // Chunk 1 should be committed (excluding tail, i.e. first sector only)
        assertEquals(sectorSize, committed2?.size)
        val expectedCommitted1 = chunk1.copyOfRange(0, sectorSize)
        assertArrayEquals(expectedCommitted1, committed2)

        // Finalize
        val finalCommitted = verifier.finalize()
        assertEquals(chunk2.size, finalCommitted?.size)
        assertArrayEquals(chunk2, finalCommitted)
    }
}
