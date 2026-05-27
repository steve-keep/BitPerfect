package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class DriftDetectorTest {

    private val detector = DriftDetector()

    private fun generateSequence(start: Int, samples: Int): ByteArray {
        val result = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = start + i
            result[i * 2] = (v and 0xFF).toByte()
            result[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return result
    }

    @Test
    fun testExactAlignmentReturnsNull() {
        val expected = generateSequence(1, 100)
        val observed = expected.copyOf()
        val result = detector.analyze(expected, observed)
        assertNull("Exact alignment should return null", result)
    }

    @Test
    fun testPositiveShift() {
        val base = generateSequence(1, 100)
        val expected = base.copyOf()
        val observed = generateSequence(0, 4) + base.copyOfRange(0, 96 * 2)

        val result = detector.analyze(expected, observed)
        assertNotNull("Should detect drift", result)
        assertEquals(4, result!!.shiftSamples)
        assertEquals(0, result.expectedOffset)
        assertEquals(4, result.observedOffset)
        assertEquals(DriftConfidence.HIGH, result.confidence)
        assertEquals(96, result.overlapMatchLength)
    }

    @Test
    fun testNegativeShift() {
        val base = generateSequence(1, 100)
        val expected = generateSequence(0, 4) + base.copyOfRange(0, 96 * 2)
        val observed = base.copyOf()

        val result = detector.analyze(expected, observed)
        assertNotNull("Should detect drift", result)
        assertEquals(-4, result!!.shiftSamples)
        assertEquals(4, result.expectedOffset)
        assertEquals(0, result.observedOffset)
        assertEquals(DriftConfidence.HIGH, result.confidence)
        assertEquals(96, result.overlapMatchLength)
    }

    @Test
    fun testNoisyMismatchReturnsNull() {
        val expected = generateSequence(1, 100)
        val observed = generateSequence(500, 100)
        val result = detector.analyze(expected, observed)
        assertNull("Noisy mismatch should return null", result)
    }

    @Test
    fun testLargeInvalidShiftReturnsNull() {
        val base = generateSequence(1, 100)
        val expected = base.copyOf()
        // shift of 15 samples (exceeds max 12)
        val observed = generateSequence(0, 15) + base.copyOfRange(0, 85 * 2)
        val result = detector.analyze(expected, observed)
        assertNull("Shift exceeding max bounds should return null", result)
    }

    @Test
    fun testConfidenceLevels() {
        val base = generateSequence(1, 100)
        val expected = base.copyOf()
        // We need a negative shift to match the logic I just updated
        val observed = generateSequence(0, 4) + base.copyOfRange(0, 96 * 2)

        // Match = 96/96 (100%) -> HIGH
        var result = detector.analyze(expected, observed)
        assertEquals(DriftConfidence.HIGH, result!!.confidence)

        // Make it ~92% (88 contiguous match out of 96) -> MEDIUM
        // Let's break it at index 4 (which maps to sample 8 in observed)
        observed[8 * 2] = 0
        result = detector.analyze(expected, observed)
        assertEquals(DriftConfidence.MEDIUM, result!!.confidence)

        // Make it ~80% (77 contiguous match out of 96) -> LOW
        observed[8 * 2 + 15 * 2] = 0 // Break again earlier
        result = detector.analyze(expected, observed)
        assertEquals(DriftConfidence.LOW, result!!.confidence)

        // Make it <75% -> null
        observed[8 * 2 + 15 * 2 + 10 * 2] = 0
        observed[8 * 2 + 15 * 2 + 20 * 2] = 0
        result = detector.analyze(expected, observed)
        assertNull("Confidence < 75% should return null", result)
    }
}
