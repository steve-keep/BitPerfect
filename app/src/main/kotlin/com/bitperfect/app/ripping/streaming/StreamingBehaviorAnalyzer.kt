package com.bitperfect.app.ripping.streaming

import com.bitperfect.core.utils.AppLogger
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

interface StreamingBehaviorAnalyzer {
    fun analyze(reads: List<SequentialReadTelemetry>): StreamingAnalysisResult
}

class DefaultStreamingBehaviorAnalyzer : StreamingBehaviorAnalyzer {
    override fun analyze(reads: List<SequentialReadTelemetry>): StreamingAnalysisResult {
        if (reads.isEmpty()) {
            return StreamingAnalysisResult(
                classification = StreamingClassification.STABLE_STREAMING,
                metrics = StreamingMetrics(0.0, 0.0, 0.0, 1.0f, 0.0f, 0)
            )
        }

        // We only care about normal sequential reads for overall metrics
        val sequentialReads = reads.filter { !it.wasRecoveryRead }
        if (sequentialReads.isEmpty()) {
            return StreamingAnalysisResult(
                classification = StreamingClassification.UNSTABLE_STREAMING,
                metrics = StreamingMetrics(0.0, 0.0, 0.0, 0.0f, 0.0f, 0)
            )
        }

        val averageLatencyMs = sequentialReads.map { it.durationMs }.average()
        val latencyVarianceMs = if (sequentialReads.size > 1) {
            val mean = averageLatencyMs
            val variance = sequentialReads.sumOf { (it.durationMs - mean).pow(2.0) } / (sequentialReads.size - 1)
            sqrt(variance)
        } else {
            0.0
        }

        val maxLatencySpikeMs = sequentialReads.maxOfOrNull { it.durationMs } ?: 0.0

        // Determine scores based on variance and relative spikes
        val sequentialConsistencyScore = max(0.0, 1.0 - (latencyVarianceMs / max(1.0, averageLatencyMs))).toFloat().coerceIn(0.0f, 1.0f)

        val postSeekReads = reads.filter { it.followedSeekRecovery && !it.wasRecoveryRead }
        val postSeekDegradationScore = if (postSeekReads.isNotEmpty() && sequentialReads.isNotEmpty()) {
            val avgPostSeekLatency = postSeekReads.map { it.durationMs }.average()
            val ratio = avgPostSeekLatency / averageLatencyMs
            if (ratio > 1.0) {
                ((ratio - 1.0) / 2.0).toFloat().coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }
        } else {
            0.0f
        }

        // Emit debug logs for specific anomalies per chunk
        for (read in sequentialReads) {
            if (averageLatencyMs > 0 && read.durationMs > averageLatencyMs * 3) {
                AppLogger.d("StreamingBehaviorAnalyzer", "latency spike detected: duration=${read.durationMs}ms (avg=${averageLatencyMs}ms) window=${read.startLba}-${read.endLba}")
            }
        }
        for (read in postSeekReads) {
             if (averageLatencyMs > 0 && read.durationMs > averageLatencyMs * 2) {
                AppLogger.d("StreamingBehaviorAnalyzer", "post-seek instability observed: duration=${read.durationMs}ms (avg=${averageLatencyMs}ms) window=${read.startLba}-${read.endLba}")
             }
        }

        // Final track-level classification using specified thresholds
        val classification = when {
            sequentialConsistencyScore >= 0.80f -> StreamingClassification.STABLE_STREAMING
            sequentialConsistencyScore >= 0.45f -> StreamingClassification.PARTIAL_STREAMING
            else -> StreamingClassification.UNSTABLE_STREAMING
        }

        return StreamingAnalysisResult(
            classification = classification,
            metrics = StreamingMetrics(
                averageLatencyMs = averageLatencyMs,
                latencyVarianceMs = latencyVarianceMs,
                maxLatencySpikeMs = maxLatencySpikeMs,
                sequentialConsistencyScore = sequentialConsistencyScore,
                postSeekDegradationScore = postSeekDegradationScore,
                sequentialReadCount = sequentialReads.size
            )
        )
    }
}
