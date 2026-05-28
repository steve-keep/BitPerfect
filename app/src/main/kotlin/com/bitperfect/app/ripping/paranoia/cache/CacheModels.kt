package com.bitperfect.app.ripping.paranoia.cache

enum class CacheStatus {
    CACHE_UNLIKELY,
    CACHE_SUSPECTED,
    CACHE_CONFIRMED
}

data class CacheProbeResult(
    val status: CacheStatus,
    val suspicionScore: Float,
    val identicalRereadCount: Int,
    val averageInitialReadLatencyMs: Double,
    val averageRereadLatencyMs: Double,
    val rereadTimingVarianceMs: Double
)

data class ReadAttempt(
    val startLba: Int,
    val endLba: Int,
    val durationMs: Double,
    val wasReread: Boolean,
    val matchedPreviousAttempt: Boolean,
    val mismatchRegions: List<IntRange>,
    val checksum: Long
)
