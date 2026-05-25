package com.bitperfect.app.ripping.paranoia.anomaly

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min

class AlignmentAnalyzerTest {

    private val analyzer = AlignmentAnalyzer()

    @Test
    fun `test aligned overlaps returns None`() {
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val actual = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = analyzer.analyze(expected, actual)
        assertEquals(AlignmentAnomaly.None, result)
    }

    @Test
    fun `test positive shift returns PossibleShift`() {
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val actual = byteArrayOf(0, 0, 1, 2, 3, 4, 5, 6)
        val result = analyzer.analyze(expected, actual)
        assertTrue(result is AlignmentAnomaly.PossibleShift)
    }

    @Test
    fun `test negative shift returns PossibleShift`() {
        val expected = byteArrayOf(0, 0, 1, 2, 3, 4, 5, 6)
        val actual = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val result = analyzer.analyze(expected, actual)
        assertTrue(result is AlignmentAnomaly.PossibleShift)
    }

    @Test
    fun `test random corruption returns SevereInstability`() {
        val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val actual = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)
        val result = analyzer.analyze(expected, actual)
        assertTrue(result is AlignmentAnomaly.SevereInstability)
    }

    @Test
    fun `test shifted overlap with single corrupted sample returns PossibleShift with high confidence`() {
        val expected = ByteArray(400) { it.toByte() }
        val actual = ByteArray(400)
        System.arraycopy(expected, 0, actual, 4, 396)
        actual[10] = 99
        actual[11] = 99
        val result = analyzer.analyze(expected, actual)
        assertTrue(result is AlignmentAnomaly.PossibleShift)
    }
}
