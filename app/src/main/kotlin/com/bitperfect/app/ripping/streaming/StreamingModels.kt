package com.bitperfect.app.ripping.streaming

enum class StreamingClassification {
    STABLE_STREAMING,
    PARTIAL_STREAMING,
    UNSTABLE_STREAMING
}

data class StreamingMetrics(
    val sequentialReadCount: Int,
    val stallEvents: Int,
    val longestStallMs: Double,
    val stallPercentage: Float
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
