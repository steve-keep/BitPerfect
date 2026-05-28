package com.bitperfect.app.ripping.paranoia.cache

import com.bitperfect.core.utils.AppLogger
import kotlin.math.pow

interface DriveCacheAnalyzer {
    fun analyze(attempts: List<ReadAttempt>): CacheProbeResult
}

class DefaultDriveCacheAnalyzer : DriveCacheAnalyzer {

    override fun analyze(attempts: List<ReadAttempt>): CacheProbeResult {
        if (attempts.isEmpty() || attempts.size == 1) {
            return CacheProbeResult(
                status = CacheStatus.CACHE_UNLIKELY,
                suspicionScore = 0.0f,
                identicalRereadCount = 0,
                averageInitialReadLatencyMs = attempts.firstOrNull()?.durationMs ?: 0.0,
                averageRereadLatencyMs = 0.0,
                rereadTimingVarianceMs = 0.0
            )
        }

        val initialRead = attempts.first()
        val rereads = attempts.drop(1)

        val identicalCount = rereads.count { it.matchedPreviousAttempt }

        val initialLatency = initialRead.durationMs
        val avgRereadLatency = rereads.map { it.durationMs }.average()

        val variance = if (rereads.size > 1) {
            val mean = avgRereadLatency
            rereads.map { (it.durationMs - mean).pow(2) }.average()
        } else {
            0.0
        }

        var score = 0.0f

        // Signal: Identical corruption repeated multiple times
        // We only care about it if there are multiple rereads, meaning deterministic failure
        if (identicalCount >= 2) {
            // max identical signal weight if heavily repeated
            val identicalRatio = identicalCount.toFloat() / rereads.size
            score += 0.45f * identicalRatio
        }

        // Signal: Latency collapse (rereads much faster than initial)
        val latencyRatio = if (initialLatency > 0.0) avgRereadLatency / initialLatency else 1.0
        if (latencyRatio < 0.2) {
            // Suspiciously fast compared to initial read
            score += 0.35f
        } else if (latencyRatio < 0.5) {
            score += 0.15f
        }

        // Signal: Low reread timing variance (deterministic cache hits are typically very uniform)
        // If variance is less than 1ms squared (meaning very tight cluster)
        if (rereads.size >= 2 && variance < 2.0) {
            score += 0.20f
        }

        score = score.coerceIn(0.0f, 1.0f)

        val status = when {
            score >= 0.75f -> CacheStatus.CACHE_CONFIRMED
            score >= 0.40f -> CacheStatus.CACHE_SUSPECTED
            else -> CacheStatus.CACHE_UNLIKELY
        }

        val result = CacheProbeResult(
            status = status,
            suspicionScore = score,
            identicalRereadCount = identicalCount,
            averageInitialReadLatencyMs = initialLatency,
            averageRereadLatencyMs = avgRereadLatency,
            rereadTimingVarianceMs = variance
        )

        if (status != CacheStatus.CACHE_UNLIKELY) {
            val startLba = attempts.first().startLba
            val endLba = attempts.first().endLba
            val latencyStr = String.format(java.util.Locale.US, "%.2f", latencyRatio)
            val scoreStr = String.format(java.util.Locale.US, "%.2f", score)
            AppLogger.d("DriveCacheAnalyzer",
                "Suspicious reread behaviour detected " +
                "window=\$startLba-\$endLba " +
                "identicalAttempts=\$identicalCount " +
                "latencyRatio=\$latencyStr " +
                "status=\$status " +
                "score=\$scoreStr"
            )
        }

        return result
    }
}
