package com.bitperfect.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class SecureRipPipelineTest {

    // Helper to simulate the overlap extraction
    private fun simulateOverlap(chunk1: ByteArray, chunk2: ByteArray, overlapSize: Int): Boolean {
        val previousTail = chunk1.overlapTail(overlapSize)
        val currentHead = chunk2.overlapHead(overlapSize)
        val effectiveOverlap = minOf(previousTail.size, currentHead.size)
        return previousTail.overlapTail(effectiveOverlap).contentEquals(currentHead.overlapHead(effectiveOverlap))
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
            out.write(p1.pcm.dropLast(overlap).toByteArray())
        }

        // Loop 3 starts: chunk2 overlaps with chunk3 by 2 bytes
        val p3 = PendingChunk(6, 9, chunk3)
        val match2 = simulateOverlap(p2.pcm, p3.pcm, overlap)
        assertEquals(true, match2)
        if (match2) {
            out.write(p2.pcm.dropLast(overlap).toByteArray())
        }

        // EOF: commit the last pending chunk entirely
        out.write(p3.pcm)

        val result = out.toByteArray()
        val expected = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertArrayEquals(expected, result)
    }
}
