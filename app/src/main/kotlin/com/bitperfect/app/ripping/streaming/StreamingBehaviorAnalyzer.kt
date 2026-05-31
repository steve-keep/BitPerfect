package com.bitperfect.app.ripping.streaming

import com.bitperfect.core.utils.AppLogger

interface StreamingBehaviorAnalyzer {
    fun analyze(
        reads: List<SequentialReadTelemetry>,
        overlapFailures: Int = 0,
        rereads: Int = 0,
        recoveryWindows: Int = 0
    ): StreamingAnalysisResult
}

class DefaultStreamingBehaviorAnalyzer : StreamingBehaviorAnalyzer {
    override fun analyze(
        reads: List<SequentialReadTelemetry>,
        overlapFailures: Int,
        rereads: Int,
        recoveryWindows: Int
    ): StreamingAnalysisResult {
        if (reads.isEmpty()) {
            return StreamingAnalysisResult(
                classification = StreamingClassification.STABLE_STREAMING,
                metrics = StreamingMetrics(0, 0, 0.0, 0.0f)
            )
        }

        // We only care about normal sequential reads for overall metrics
        val sequentialReads = reads.filter { !it.wasRecoveryRead }
        if (sequentialReads.isEmpty()) {
            return StreamingAnalysisResult(
                classification = StreamingClassification.UNSTABLE_STREAMING,
                metrics = StreamingMetrics(0, 0, 0.0, 0.0f)
            )
        }

        val stallThresholdMs = 250.0
        val stallReads = sequentialReads.filter { it.durationMs > stallThresholdMs }
        val stallEvents = stallReads.size
        val longestStallMs = stallReads.maxOfOrNull { it.durationMs } ?: 0.0
        val stallPercentage = (stallEvents.toFloat() / sequentialReads.size.toFloat()) * 100f

        val hasPerfectRecoveryMetrics = overlapFailures == 0 && rereads == 0 && recoveryWindows == 0

        val classification = when {
            stallPercentage < 1.0f && hasPerfectRecoveryMetrics -> StreamingClassification.STABLE_STREAMING
            stallPercentage < 1.0f -> StreamingClassification.STABLE_STREAMING
            stallPercentage <= 5.0f -> StreamingClassification.PARTIAL_STREAMING
            else -> StreamingClassification.UNSTABLE_STREAMING
        }

        return StreamingAnalysisResult(
            classification = classification,
            metrics = StreamingMetrics(
                sequentialReadCount = sequentialReads.size,
                stallEvents = stallEvents,
                longestStallMs = longestStallMs,
                stallPercentage = stallPercentage
            )
        )
    }
}
