package com.bitperfect.app.ripping.paranoia

import org.junit.Assert.assertEquals
import org.junit.Test

class RipConfidenceEvaluatorTest {
    private val evaluator = RipConfidenceEvaluator()

    @Test
    fun `stable read without rereads is HIGH`() {
        val confidence = evaluator.evaluateChunkConfidence(
            overlapMatchedImmediately = true,
            rereadsPerformed = 0,
            recoverySucceeded = false // shouldn't matter
        )
        assertEquals(RipConfidence.HIGH, confidence)
    }

    @Test
    fun `successful recovery is MEDIUM`() {
        val confidence = evaluator.evaluateChunkConfidence(
            overlapMatchedImmediately = false,
            rereadsPerformed = 3,
            recoverySucceeded = true
        )
        assertEquals(RipConfidence.MEDIUM, confidence)
    }

    @Test
    fun `failed recovery is DAMAGED`() {
        val confidence = evaluator.evaluateChunkConfidence(
            overlapMatchedImmediately = false,
            rereadsPerformed = 5,
            recoverySucceeded = false
        )
        assertEquals(RipConfidence.DAMAGED, confidence)
    }

    @Test
    fun `alignment confidence critical issue is DAMAGED`() {
        val confidence = evaluator.evaluateAlignmentConfidence(
            current = RipConfidence.HIGH,
            validation = AlignmentValidationResult(
                valid = false,
                anomalies = listOf(AlignmentIssue.DuplicateSamples(5, 10))
            )
        )
        assertEquals(RipConfidence.DAMAGED, confidence)
    }

    @Test
    fun `track confidence monotonically degrades`() {
        var current = RipConfidence.HIGH

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.HIGH)
        assertEquals(RipConfidence.HIGH, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.MEDIUM)
        assertEquals(RipConfidence.MEDIUM, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.HIGH)
        assertEquals(RipConfidence.MEDIUM, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.LOW)
        assertEquals(RipConfidence.LOW, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.MEDIUM)
        assertEquals(RipConfidence.LOW, current)

        // explicit regression tests
        current = evaluator.aggregateTrackConfidence(current, RipConfidence.HIGH)
        assertEquals(RipConfidence.LOW, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.DAMAGED)
        assertEquals(RipConfidence.DAMAGED, current)

        current = evaluator.aggregateTrackConfidence(current, RipConfidence.HIGH)
        assertEquals(RipConfidence.DAMAGED, current)
    }

    @Test
    fun `mixed track aggregation never improves`() {
        var current = RipConfidence.HIGH

        val sequence = listOf(
            RipConfidence.HIGH,
            RipConfidence.MEDIUM,
            RipConfidence.LOW,
            RipConfidence.HIGH
        )

        for (conf in sequence) {
            current = evaluator.aggregateTrackConfidence(current, conf)
        }

        assertEquals(RipConfidence.LOW, current)
    }
}
