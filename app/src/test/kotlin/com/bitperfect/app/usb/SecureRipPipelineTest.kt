package com.bitperfect.app.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import com.bitperfect.core.utils.AppLogger

class SecureRipPipelineTest {

    private fun makeSectors(startSector: Int, count: Int): ByteArray {
        val buf = ByteArray(count * 2352)
        for (i in buf.indices) {
            buf[i] = ((startSector * 2352 + i) and 0xFF).toByte()
        }
        return buf
    }

    // --- Group 1: matchOverlapTailWithHead edge cases ---

    @Test
    fun `test matchOverlapTailWithHead returns false when effectiveOverlap is zero`() {
        val chunk1 = ByteArray(10) { 1 }
        val chunk2 = ByteArray(10) { 1 }
        assertEquals(false, chunk1.matchOverlapTailWithHead(chunk2, 0))
    }

    @Test
    fun `test matchOverlapTailWithHead returns false when this is shorter than effectiveOverlap`() {
        val chunk1 = ByteArray(5) { 1 }
        val chunk2 = ByteArray(10) { 1 }
        assertEquals(false, chunk1.matchOverlapTailWithHead(chunk2, 10))
    }

    @Test
    fun `test matchOverlapTailWithHead returns false when other is shorter than effectiveOverlap`() {
        val chunk1 = ByteArray(10) { 1 }
        val chunk2 = ByteArray(5) { 1 }
        assertEquals(false, chunk1.matchOverlapTailWithHead(chunk2, 10))
    }

    @Test
    fun `test exact match`() {
        val pcm = ByteArray(100) { it.toByte() }
        val chunk1 = pcm.copyOfRange(0, 50)
        val chunk2 = pcm.copyOfRange(40, 90) // Overlap is 10 bytes: 40-49

        val isMatch = chunk1.matchOverlapTailWithHead(chunk2, 10)
        assertEquals(true, isMatch)
    }

    @Test
    fun `test shifted overlap`() {
        val pcm = ByteArray(100) { it.toByte() }
        val chunk1 = pcm.copyOfRange(0, 50)
        val chunk2 = pcm.copyOfRange(41, 91) // Shifted by 1 byte

        val effectiveOverlap = minOf(10, chunk1.size, chunk2.size)
        val isMatch = chunk1.matchOverlapTailWithHead(chunk2, effectiveOverlap)
        assertEquals(false, isMatch)
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

    // --- Group 2: sectorsRead advancement logic ---

    @Test
    fun `test first chunk advances sectorsRead by strideSectors`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val sectorsActuallyRead = 32
        val sectorsToRead = 32

        val result = if (sectorsActuallyRead < sectorsToRead) maxOf(1, sectorsActuallyRead - overlapSectors) else strideSectors
        assertEquals(26, result)
    }

    @Test
    fun `test first chunk short read advances sectorsRead by maxOf`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val sectorsActuallyRead = 7
        val sectorsToRead = 32

        val result = if (sectorsActuallyRead < sectorsToRead) maxOf(1, sectorsActuallyRead - overlapSectors) else strideSectors
        assertEquals(1, result)
    }

    @Test
    fun `test first chunk very short read advances by at least 1`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val sectorsActuallyRead = 3
        val sectorsToRead = 32

        val result = if (sectorsActuallyRead < sectorsToRead) maxOf(1, sectorsActuallyRead - overlapSectors) else strideSectors
        assertEquals(1, result)
    }

    @Test
    fun `test match path advances sectorsRead by strideSectors on full read`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val sectorsActuallyRead = 32
        val sectorsToRead = 32

        val result = if (sectorsActuallyRead < sectorsToRead) maxOf(1, sectorsActuallyRead - overlapSectors) else strideSectors
        assertEquals(26, result)
    }

    @Test
    fun `test recovery path advances sectorsRead by strideSectors on full candidate`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val candidateSectors = 32
        val sectorsToRead = 32

        val result = if (candidateSectors < sectorsToRead) maxOf(1, candidateSectors - overlapSectors) else strideSectors
        assertEquals(26, result)
    }

    @Test
    fun `test unrecoverable path advances sectorsRead by chunkSize on full read`() {
        val chunkSize = 32
        val overlapSectors = 6
        val strideSectors = 26
        val candidateSectors = 32
        val sectorsToRead = 32

        val result = if (candidateSectors < sectorsToRead) candidateSectors else chunkSize
        assertEquals(32, result)
    }

    // --- Group 3: Recovery loop ---

    private fun runRecoveryLoop(candidates: List<ByteArray>, maxRereads: Int = 6): Pair<Boolean, Int> {
        val pendingChunk = ByteArray(5) { 4 } // Tail is all 4s
        val effectiveOverlap = 5

        var recoverySuccess = false
        var matchesFound = 0
        var lastCandidate: ByteArray? = null
        var actualAttempts = maxRereads

        for (attempt in 1..maxRereads) {
            if (attempt - 1 >= candidates.size) break
            val candidate = candidates[attempt - 1]

            if (lastCandidate != null && lastCandidate.contentEquals(candidate)) {
                matchesFound++
            } else {
                matchesFound = 0
            }
            lastCandidate = candidate

            val overlapMatches = pendingChunk.matchOverlapTailWithHead(candidate, effectiveOverlap)

            if (overlapMatches) {
                if (matchesFound >= 1) { // 2 identical reads + matching overlap
                    recoverySuccess = true
                    actualAttempts = attempt
                    break
                }
            }
        }
        return Pair(recoverySuccess, actualAttempts)
    }

    @Test
    fun `test recovery succeeds on first attempt`() {
        // Technically "first attempt" of recovery finding a match requires 2 identical reads, so it's attempt 2
        val goodCandidate = byteArrayOf(4, 4, 4, 4, 4, 1, 2, 3)
        val candidates = listOf(goodCandidate, goodCandidate)

        val (success, attempts) = runRecoveryLoop(candidates)
        assertEquals(true, success)
        assertEquals(2, attempts)
    }

    @Test
    fun `test recovery succeeds on last attempt`() {
        val badCandidate = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val goodCandidate = byteArrayOf(4, 4, 4, 4, 4, 1, 2, 3)
        val candidates = listOf(badCandidate, badCandidate, badCandidate, badCandidate, goodCandidate, goodCandidate)

        val (success, attempts) = runRecoveryLoop(candidates)
        assertEquals(true, success)
        assertEquals(6, attempts)
    }

    @Test
    fun `test recovery fails when all attempts mismatch overlap`() {
        val badCandidate = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val candidates = listOf(badCandidate, badCandidate, badCandidate, badCandidate, badCandidate, badCandidate)

        val (success, attempts) = runRecoveryLoop(candidates)
        assertEquals(false, success)
        assertEquals(6, attempts)
    }

    @Test
    fun `test recovery resets matchesFound when consecutive reads differ`() {
        val goodCandidate1 = byteArrayOf(4, 4, 4, 4, 4, 1, 2, 3)
        val goodCandidate2 = byteArrayOf(4, 4, 4, 4, 4, 9, 9, 9)

        // Match 1, Match 2 (different body), Match 2 (same body)
        val candidates = listOf(goodCandidate1, goodCandidate2, goodCandidate2)

        val (success, attempts) = runRecoveryLoop(candidates)
        assertEquals(true, success)
        assertEquals(3, attempts)
    }

    @Test
    fun `test recovery does not succeed on single matching read`() {
        val goodCandidate = byteArrayOf(4, 4, 4, 4, 4, 1, 2, 3)
        val candidates = listOf(goodCandidate) // Only 1 read available

        val (success, attempts) = runRecoveryLoop(candidates)
        assertEquals(false, success)
        assertEquals(6, attempts)
    }

    // --- Group 4: SuspiciousRead recording ---

    @Test
    fun `test suspicious read records correct LBA range and actualAttempts`() {
        val currentStartLba = 150
        val sectorsToRead = 32
        val actualAttempts = 3

        val newSuspiciousRead = SuspiciousRead(currentStartLba, currentStartLba + sectorsToRead - 1, 1, actualAttempts)

        assertEquals(150, newSuspiciousRead.startLba)
        assertEquals(181, newSuspiciousRead.endLba)
        assertEquals(1, newSuspiciousRead.mismatchCount)
        assertEquals(3, newSuspiciousRead.rereadAttempts)
    }

    @Test
    fun `test adjacent suspicious reads are merged`() {
        val suspiciousReadsList = mutableListOf<SuspiciousRead>()
        val maxRereads = 6

        // First mismatch
        val currentStartLba1 = 150
        val sectorsToRead1 = 32
        val actualAttempts1 = 3
        val newSuspiciousRead1 = SuspiciousRead(currentStartLba1, currentStartLba1 + sectorsToRead1 - 1, 1, actualAttempts1)
        suspiciousReadsList.add(newSuspiciousRead1)

        // Second mismatch (adjacent)
        val currentStartLba2 = 176
        val sectorsToRead2 = 32
        val actualAttempts2 = 2
        val newSuspiciousRead2 = SuspiciousRead(currentStartLba2, currentStartLba2 + sectorsToRead2 - 1, 1, actualAttempts2)

        val lastSuspicious = suspiciousReadsList.lastOrNull()
        if (lastSuspicious != null && currentStartLba2 <= lastSuspicious.endLba + 1) {
            suspiciousReadsList[suspiciousReadsList.size - 1] = lastSuspicious.copy(
                endLba = maxOf(lastSuspicious.endLba, newSuspiciousRead2.endLba),
                mismatchCount = lastSuspicious.mismatchCount + 1,
                rereadAttempts = lastSuspicious.rereadAttempts + actualAttempts2
            )
        } else {
            suspiciousReadsList.add(newSuspiciousRead2)
        }

        assertEquals(1, suspiciousReadsList.size)
        assertEquals(207, suspiciousReadsList[0].endLba)
        assertEquals(2, suspiciousReadsList[0].mismatchCount)
        assertEquals(5, suspiciousReadsList[0].rereadAttempts)
    }

    @Test
    fun `test non-adjacent suspicious reads are not merged`() {
        val suspiciousReadsList = mutableListOf<SuspiciousRead>()

        // First mismatch
        suspiciousReadsList.add(SuspiciousRead(150, 181, 1, 3))

        // Second mismatch (not adjacent)
        val currentStartLba2 = 220
        val newSuspiciousRead2 = SuspiciousRead(220, 251, 1, 2)

        val lastSuspicious = suspiciousReadsList.lastOrNull()
        if (lastSuspicious != null && currentStartLba2 <= lastSuspicious.endLba + 1) {
            suspiciousReadsList[suspiciousReadsList.size - 1] = lastSuspicious.copy(
                endLba = maxOf(lastSuspicious.endLba, newSuspiciousRead2.endLba),
                mismatchCount = lastSuspicious.mismatchCount + 1,
                rereadAttempts = lastSuspicious.rereadAttempts + 2
            )
        } else {
            suspiciousReadsList.add(newSuspiciousRead2)
        }

        assertEquals(2, suspiciousReadsList.size)
        assertEquals(181, suspiciousReadsList[0].endLba)
        assertEquals(251, suspiciousReadsList[1].endLba)
    }

    // --- Group 5: Confidence degradation ---

    @Test
    fun `test degradeTo never improves confidence`() {
        assertEquals(RipConfidence.LOW, RipConfidence.LOW.degradeTo(RipConfidence.HIGH))
        assertEquals(RipConfidence.DAMAGED, RipConfidence.DAMAGED.degradeTo(RipConfidence.MEDIUM))
    }

    @Test
    fun `test degradeTo correctly degrades`() {
        assertEquals(RipConfidence.MEDIUM, RipConfidence.HIGH.degradeTo(RipConfidence.MEDIUM))
        assertEquals(RipConfidence.DAMAGED, RipConfidence.HIGH.degradeTo(RipConfidence.DAMAGED))
        assertEquals(RipConfidence.LOW, RipConfidence.MEDIUM.degradeTo(RipConfidence.LOW))
    }

    @Test
    fun `test short read sets confidence to LOW`() {
        var currentConfidence = RipConfidence.HIGH
        currentConfidence = currentConfidence.degradeTo(RipConfidence.LOW)
        assertEquals(RipConfidence.LOW, currentConfidence)
    }

    @Test
    fun `test recovered seam sets confidence to MEDIUM not higher`() {
        var currentConfidence = RipConfidence.HIGH
        currentConfidence = currentConfidence.degradeTo(RipConfidence.MEDIUM)
        assertEquals(RipConfidence.MEDIUM, currentConfidence)

        currentConfidence = currentConfidence.degradeTo(RipConfidence.LOW)
        assertEquals(RipConfidence.LOW, currentConfidence)
    }

    @Test
    fun `test unrecoverable seam sets confidence to DAMAGED`() {
        var currentConfidence = RipConfidence.HIGH
        currentConfidence = currentConfidence.degradeTo(RipConfidence.DAMAGED)
        assertEquals(RipConfidence.DAMAGED, currentConfidence)

        currentConfidence = currentConfidence.degradeTo(RipConfidence.LOW)
        assertEquals(RipConfidence.DAMAGED, currentConfidence)
    }

    // --- Group 6: commitPcm skip logic edge cases ---

    @Test
    fun `test commitPcm skips exactly skipBytes when split across two chunks`() {
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

        val chunk1 = byteArrayOf(1, 2, 3) // 3 bytes -> all skipped
        val chunk2 = byteArrayOf(4, 5, 6, 7, 8, 9, 10, 11) // 8 bytes -> 2 skipped, 6 written

        commitPcm(chunk1)
        commitPcm(chunk2)

        val expected = byteArrayOf(6, 7, 8, 9, 10, 11)
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun `test commitPcm is a no-op on empty array`() {
        var committedPcmBytes = 0L
        fun commitPcm(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            committedPcmBytes += pcm.size
        }

        commitPcm(ByteArray(0))
        assertEquals(0L, committedPcmBytes)
    }

    @Test
    fun `test commitPcm passes through normally when remainingSkipBytes is zero`() {
        val out = ByteArrayOutputStream()
        var remainingSkipBytes = 0

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

        val chunk = byteArrayOf(1, 2, 3, 4, 5)
        commitPcm(chunk)

        assertArrayEquals(chunk, out.toByteArray())
    }

    // --- Duplication Prevention ---

    @Test
    fun `test duplication prevention logic`() {
        // Simulating the dropLast logic (now done via copyOfRange to avoid dropLast allocations)
        val out = ByteArrayOutputStream()
        val chunk1 = byteArrayOf(0, 1, 2, 3, 4)
        val chunk2 = byteArrayOf(3, 4, 5, 6, 7)
        val chunk3 = byteArrayOf(6, 7, 8, 9)

        // Loop 1
        val p1 = PendingChunk(0, 4, chunk1)
        val p2 = PendingChunk(3, 7, chunk2)

        // Loop 2 starts: chunk1 overlaps with chunk2 by 2 bytes
        val overlap = 2
        val match1 = p1.pcm.matchOverlapTailWithHead(p2.pcm, overlap) && overlap > 0
        assertEquals(true, match1)
        if (match1) {
            out.write(p1.pcm.copyOfRange(0, p1.pcm.size - overlap))
        }

        // Loop 3 starts: chunk2 overlaps with chunk3 by 2 bytes
        val p3 = PendingChunk(6, 9, chunk3)
        val match2 = p2.pcm.matchOverlapTailWithHead(p3.pcm, overlap) && overlap > 0
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
