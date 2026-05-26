package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class MultiPassComparatorTest {

    private val comparator = MultiPassComparator()

    private fun b(char: Char): ByteArray {
        val array = ByteArray(1024) { char.code.toByte() }
        return array
    }

    private fun crc(char: Char): Long {
        val crc = CRC32()
        crc.update(b(char))
        return crc.value
    }

    @Test
    fun `analyze returns safe failure for empty input`() {
        val result = comparator.analyze(emptyList())

        assertEquals(InstabilityType.NONE, result.instabilityType)
        assertTrue(result.candidates.isEmpty())
        assertNull(result.stableCandidate)
        assertEquals(0, result.totalAttempts)
        assertEquals(0, result.uniqueCandidates)
    }

    @Test
    fun `stable convergence - single candidate A`() {
        val result = comparator.analyze(listOf(b('A')))

        assertEquals(InstabilityType.NONE, result.instabilityType)
        assertNull(result.stableCandidate)
        assertEquals(1, result.uniqueCandidates)
    }

    @Test
    fun `NONE - identical candidates A, A`() {
        val result = comparator.analyze(listOf(b('A'), b('A')))

        assertEquals(InstabilityType.NONE, result.instabilityType)
        assertEquals(1, result.uniqueCandidates)
        assertNotNull(result.stableCandidate)
        assertEquals(crc('A'), result.stableCandidate?.crc32)
        assertEquals(2, result.stableCandidate?.occurrenceCount)
    }

    @Test
    fun `TRANSIENT_MISMATCH - A, B, B`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('B')))

        assertEquals(InstabilityType.TRANSIENT_MISMATCH, result.instabilityType)
        assertEquals(2, result.uniqueCandidates)
        assertNotNull(result.stableCandidate)
        assertEquals(crc('B'), result.stableCandidate?.crc32)
        assertEquals(2, result.stableCandidate?.occurrenceCount)
    }

    @Test
    fun `TRANSIENT_MISMATCH - A, A, B, B`() {
        val result = comparator.analyze(listOf(b('A'), b('A'), b('B'), b('B')))

        assertEquals(InstabilityType.TRANSIENT_MISMATCH, result.instabilityType)
    }

    @Test
    fun `STABLE_CONVERGENCE - A, B, A`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('A')))

        assertEquals(InstabilityType.STABLE_CONVERGENCE, result.instabilityType)
    }

    @Test
    fun `STABLE_CONVERGENCE - A, B, C, A, A`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('C'), b('A'), b('A')))

        assertEquals(InstabilityType.STABLE_CONVERGENCE, result.instabilityType)
    }

    @Test
    fun `OSCILLATING_MISMATCH - A, B, A, B`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('A'), b('B')))

        assertEquals(InstabilityType.OSCILLATING_MISMATCH, result.instabilityType)
        assertNotNull(result.stableCandidate)
    }

    @Test
    fun `OSCILLATING_MISMATCH - A, B, A, B, A`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('A'), b('B'), b('A')))

        assertEquals(InstabilityType.OSCILLATING_MISMATCH, result.instabilityType)
    }

    @Test
    fun `PERSISTENT_INSTABILITY - A, B, C, D`() {
        val result = comparator.analyze(listOf(b('A'), b('B'), b('C'), b('D')))

        assertEquals(InstabilityType.PERSISTENT_INSTABILITY, result.instabilityType)
        assertNull(result.stableCandidate)
    }
}
