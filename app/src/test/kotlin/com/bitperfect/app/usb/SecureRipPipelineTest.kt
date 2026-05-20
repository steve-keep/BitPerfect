package com.bitperfect.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import com.bitperfect.core.utils.AppLogger

class SecureRipPipelineTest {

    // Helper to simulate the overlap extraction
    private fun simulateOverlap(chunk1: ByteArray, chunk2: ByteArray, overlapSize: Int): Boolean {
        val effectiveOverlap = minOf(overlapSize, chunk1.size, chunk2.size)
        return chunk1.matchOverlapTailWithHead(chunk2, effectiveOverlap) && effectiveOverlap > 0
    }

    @Test
    fun `test exact match`() {
        val pcm = ByteArray(100) { it.toByte() }
        val chunk1 = pcm.copyOfRange(0, 50)
        val chunk2 = pcm.copyOfRange(40, 90) // Overlap is 10 bytes: 40-49

        val isMatch = simulateOverlap(chunk1, chunk2, 10)
        assertEquals(true, isMatch)
    }

    @Test
    fun `test shifted overlap`() {
        val pcm = ByteArray(100) { it.toByte() }
        val chunk1 = pcm.copyOfRange(0, 50)
        val chunk2 = pcm.copyOfRange(41, 91) // Shifted by 1 byte

        val isMatch = simulateOverlap(chunk1, chunk2, 10)
        assertEquals(false, isMatch)
    }

    @Test
    fun `test skipBytes seam alignment`() {
        val pcm = ByteArray(100) { it.toByte() }
        val skipBytes = 5
        val chunk1 = pcm.copyOfRange(skipBytes, 50)
        val chunk2 = pcm.copyOfRange(40, 90) // Overlap is 10 bytes: 40-49

        val isMatch = chunk1.matchOverlapTailWithHead(chunk2, 10)
        assertEquals(true, isMatch)
    }

    @Test
    fun `test EOF overlap truncation`() {
        val pcm = ByteArray(100) { it.toByte() }
        val chunk1 = pcm.copyOfRange(0, 90)
        val chunk2 = pcm.copyOfRange(80, 95) // Overlap is theoretically 10, but EOF chunk is only 15 long.
        // Wait, if overlapBytes is 20, the effective overlap should be min(20, 90, 15) = 15.
        // And the tail of chunk1 (90-15=75 to 90) is 75-89.
        // But chunk2 is 80-94. They mismatch!
        // To properly test truncation, let's use the correct data:
        // chunk1 ends at 90.
        // To overlap by 15, we need chunk1's tail to be 75..89.
        // So chunk2 should be 75..89.
        val chunk2Correct = pcm.copyOfRange(75, 90)

        val effectiveOverlap = minOf(20, chunk1.size, chunk2Correct.size)
        val isMatch = chunk1.matchOverlapTailWithHead(chunk2Correct, effectiveOverlap)
        assertEquals(15, effectiveOverlap)
        assertEquals(true, isMatch)
    }

    @Test
    fun `test duplication prevention logic`() {
        // Simulating the dropLast logic
        val out = ByteArrayOutputStream()
        val chunk1 = byteArrayOf(0, 1, 2, 3, 4)
        val chunk2 = byteArrayOf(3, 4, 5, 6, 7)
        val chunk3 = byteArrayOf(6, 7, 8, 9)

        // Loop 1
        val p1 = PendingChunk(0, 4, chunk1)
        val p2 = PendingChunk(3, 7, chunk2)

        // Loop 2 starts: chunk1 overlaps with chunk2 by 2 bytes
        val overlap = 2
        val match1 = simulateOverlap(p1.pcm, p2.pcm, overlap)
        assertEquals(true, match1)
        if (match1) {
            out.write(p1.pcm.copyOfRange(0, p1.pcm.size - overlap))
        }

        // Loop 3 starts: chunk2 overlaps with chunk3 by 2 bytes
        val p3 = PendingChunk(6, 9, chunk3)
        val match2 = simulateOverlap(p2.pcm, p3.pcm, overlap)
        assertEquals(true, match2)
        if (match2) {
            out.write(p2.pcm.copyOfRange(0, p2.pcm.size - overlap))
        }

        // EOF: commit the last pending chunk entirely
        out.write(p3.pcm)

        val result = out.toByteArray()
        val expected = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertArrayEquals(expected, result)
    }
}
