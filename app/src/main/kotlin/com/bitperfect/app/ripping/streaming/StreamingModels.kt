package com.bitperfect.app.ripping.streaming

enum class StreamingClassification {
    STABLE_STREAMING,
    PARTIAL_STREAMING,
    UNSTABLE_STREAMING
}

data class StreamingMetrics(
    val averageLatencyMs: Double,
    val latencyVarianceMs: Double,
    val maxLatencySpikeMs: Double,
    val sequentialConsistencyScore: Float,
    val postSeekDegradationScore: Float,
    val sequentialReadCount: Int
)

data class StreamingAnalysisResult(
    val classification: StreamingClassification,
    val metrics: StreamingMetrics
)

data class SequentialReadTelemetry(
    val startLba: Int,
    val endLba: Int,
    val durationMs: Double,
    val wasRecoveryRead: Boolean,
    val followedSeekRecovery: Boolean
)
