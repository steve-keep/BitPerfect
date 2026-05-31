package com.bitperfect.app.ripping.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingBehaviorAnalyzerTest {

    private val analyzer = DefaultStreamingBehaviorAnalyzer()

    @Test
    fun testEmptyReads() {
        val result = analyzer.analyze(emptyList())
        assertEquals(StreamingClassification.STABLE_STREAMING, result.classification)
        assertEquals(0, result.metrics.sequentialReadCount)
    }

    @Test
    fun testOnlyRecoveryReads() {
        val reads = listOf(
            SequentialReadTelemetry(0, 10, 20.0, true, false)
        )
        val result = analyzer.analyze(reads)
        assertEquals(StreamingClassification.UNSTABLE_STREAMING, result.classification)
        assertEquals(0, result.metrics.sequentialReadCount)
    }

    @Test
    fun testStableStreaming() {
        val reads = listOf(
            SequentialReadTelemetry(0, 10, 20.0, false, false),
            SequentialReadTelemetry(10, 20, 21.0, false, false),
            SequentialReadTelemetry(20, 30, 19.0, false, false),
            SequentialReadTelemetry(30, 40, 20.0, false, false)
        )
        val result = analyzer.analyze(reads)
        assertEquals(StreamingClassification.STABLE_STREAMING, result.classification)
        assertTrue(result.metrics.stallPercentage < 1.0f)
        assertEquals(4, result.metrics.sequentialReadCount)
    }

    @Test
    fun testPartialStreaming() {
        val reads = mutableListOf<SequentialReadTelemetry>()
        for (i in 0 until 98) {
            reads.add(SequentialReadTelemetry(i*10, (i+1)*10, 20.0, false, false))
        }
        reads.add(SequentialReadTelemetry(980, 990, 300.0, false, false))
        reads.add(SequentialReadTelemetry(990, 1000, 300.0, false, false))

        val result = analyzer.analyze(reads)
        assertEquals(StreamingClassification.PARTIAL_STREAMING, result.classification)
        assertEquals(100, result.metrics.sequentialReadCount)
    }

    @Test
    fun testUnstableStreaming() {
        val reads = listOf(
            SequentialReadTelemetry(0, 10, 20.0, false, false),
            SequentialReadTelemetry(10, 20, 200.0, false, false),
            SequentialReadTelemetry(20, 30, 300.0, false, false),
            SequentialReadTelemetry(30, 40, 400.0, false, false)
        )
        val result = analyzer.analyze(reads)
        assertEquals(StreamingClassification.UNSTABLE_STREAMING, result.classification)
        assertEquals(4, result.metrics.sequentialReadCount)
    }

    @Test
    fun testPostSeekDegradation() {
        val reads = listOf(
            SequentialReadTelemetry(0, 10, 20.0, false, false),
            SequentialReadTelemetry(10, 20, 20.0, false, false),
            SequentialReadTelemetry(20, 30, 150.0, true, false), // Recovery read, ignored in sequential metrics
            SequentialReadTelemetry(20, 30, 60.0, false, true), // Degraded sequential read post-seek
            SequentialReadTelemetry(30, 40, 50.0, false, false)
        )
        val result = analyzer.analyze(reads)

        assertEquals(4, result.metrics.sequentialReadCount)
    }
}
