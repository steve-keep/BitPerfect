package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.*
import org.junit.Test

class SampleAlignmentValidatorTest {

    private val validator = SampleAlignmentValidator()

    @Test
    fun testValidBoundary() {
        // Construct previous and next PCM where there are no duplicate sequence at boundary.
        val previous = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val next = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

        val result = validator.validateBoundary(previous, next, expectedTrimSamples = 6, actualTrimSamples = 6)
        assertTrue(result.valid)
        assertTrue(result.anomalies.isEmpty())
    }

    @Test
    fun testDuplicateSamplesDetection() {
        // Simulate a duplicate boundary via trim metadata.
        val previous = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val next = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

        // Actual trim is smaller than expected trim, indicating overlap was not fully removed.
        val result = validator.validateBoundary(previous, next, expectedTrimSamples = 6, actualTrimSamples = 4)

        assertFalse(result.valid)
        val anomaly = result.anomalies.find { it is AlignmentIssue.DuplicateSamples } as? AlignmentIssue.DuplicateSamples
        assertNotNull(anomaly)
        assertEquals(2, anomaly!!.sampleCount) // 6 - 4 = 2
    }

    @Test
    fun testDroppedSamplesDetection() {
        val previous = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val next = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

        // Actual trim is larger than expected trim
        val result = validator.validateBoundary(previous, next, expectedTrimSamples = 6, actualTrimSamples = 8)

        assertFalse(result.valid)
        val anomaly = result.anomalies.find { it is AlignmentIssue.DroppedSamples } as? AlignmentIssue.DroppedSamples
        assertNotNull(anomaly)
        assertEquals(2, anomaly!!.sampleCount) // 8 - 6 = 2
    }

    @Test
    fun testInvalidOverlapTrim() {
        val previous = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val next = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

        // Expected 6, actual 4 -> invalid trim
        val result = validator.validateBoundary(previous, next, expectedTrimSamples = 6, actualTrimSamples = 4)

        assertFalse(result.valid)
        val anomaly = result.anomalies.find { it is AlignmentIssue.InvalidOverlapTrim } as? AlignmentIssue.InvalidOverlapTrim
        assertNotNull(anomaly)
        assertEquals(6, anomaly!!.expectedTrimSamples)
        assertEquals(4, anomaly!!.actualTrimSamples)
    }

    @Test
    fun testValidFinalTrack() {
        val result = validator.validateFinalTrack(
            finalPcmSizeBytes = 100 * 4,
            expectedSamples = 100L,
            totalOverlapTrimmedSamples = 30L,
            expectedTotalOverlapTrimmedSamples = 30L
        )

        assertTrue(result.valid)
        assertTrue(result.anomalies.isEmpty())
    }

    @Test
    fun testFinalTrackLengthMismatch() {
        val result = validator.validateFinalTrack(
            finalPcmSizeBytes = 98 * 4,
            expectedSamples = 100L,
            totalOverlapTrimmedSamples = 30L,
            expectedTotalOverlapTrimmedSamples = 30L
        )

        assertFalse(result.valid)
        val anomaly = result.anomalies.find { it is AlignmentIssue.BoundaryDiscontinuity } as? AlignmentIssue.BoundaryDiscontinuity
        assertNotNull(anomaly)
        assertEquals(2, anomaly!!.mismatchSampleOffset) // 100 - 98 = 2
    }

    @Test
    fun testFinalTrackTrimMismatch() {
        val result = validator.validateFinalTrack(
            finalPcmSizeBytes = 100 * 4,
            expectedSamples = 100L,
            totalOverlapTrimmedSamples = 28L,
            expectedTotalOverlapTrimmedSamples = 30L
        )

        assertFalse(result.valid)
        val anomaly = result.anomalies.find { it is AlignmentIssue.InvalidOverlapTrim } as? AlignmentIssue.InvalidOverlapTrim
        assertNotNull(anomaly)
        assertEquals(30, anomaly!!.expectedTrimSamples)
        assertEquals(28, anomaly!!.actualTrimSamples)
    }
}
