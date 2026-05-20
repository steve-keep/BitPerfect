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
        val chunk2 = pcm.copyOfRange(75, 90)

        val effectiveOverlap = minOf(20, chunk1.size, chunk2.size)
        val isMatch = chunk1.matchOverlapTailWithHead(chunk2, effectiveOverlap)

        assertEquals(15, effectiveOverlap)
        assertEquals(true, isMatch)
    }

    @Test
    fun `test skipBytes prefix removal logic`() {
        val out = ByteArrayOutputStream()
        var remainingSkipBytes = 5

        fun commitPcm(pcm: ByteArray) {
            if (pcm.isEmpty()) return

            val toEncode = if (remainingSkipBytes > 0) {
                val skipAmount = minOf(remainingSkipBytes, pcm.size)
                remainingSkipBytes -= skipAmount
                if (skipAmount == pcm.size) return
                pcm.copyOfRange(skipAmount, pcm.size)
            } else {
                pcm
            }
            out.write(toEncode)
        }

        val chunk1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7) // 7 bytes
        val chunk2 = byteArrayOf(8, 9, 10) // 3 bytes

        commitPcm(chunk1)
        commitPcm(chunk2)

        // Expected: skip first 5 bytes.
        // 6, 7, 8, 9, 10
        val expected = byteArrayOf(6, 7, 8, 9, 10)
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun `test recovery loop matches requirement`() {
        // Simulating the matchesFound logic
        var matchesFound = 0
        var lastCandidate: ByteArray? = null

        val candidates = listOf(
            byteArrayOf(1, 2, 3), // Read 1 (Mismatches overlap)
            byteArrayOf(4, 5, 6), // Read 2 (Matches overlap)
            byteArrayOf(4, 5, 6)  // Read 3 (Matches overlap, identical to Read 2)
        )

        var recoverySuccess = false

        for (candidate in candidates) {
            val overlapMatches = candidate[0] == 4.toByte() // Simulating overlap match for array starting with 4
            if (overlapMatches) {
                if (lastCandidate != null && lastCandidate.contentEquals(candidate)) {
                    matchesFound++
                } else {
                    matchesFound = 0 // Reset to 0 per review
                }
                lastCandidate = candidate
                if (matchesFound >= 1) { // 1 match with previous means 2 identical reads
                    recoverySuccess = true
                    break
                }
            }
        }

        assertEquals(true, recoverySuccess)
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
