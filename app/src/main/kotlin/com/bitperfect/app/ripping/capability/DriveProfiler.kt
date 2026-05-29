package com.bitperfect.app.ripping.capability

import com.bitperfect.app.usb.DriveInfo
import com.bitperfect.app.ripping.paranoia.cache.CacheProbeResult
import com.bitperfect.app.ripping.paranoia.cache.CacheStatus
import com.bitperfect.app.ripping.streaming.StreamingAnalysisResult
import com.bitperfect.app.ripping.streaming.StreamingClassification
import com.bitperfect.app.ripping.profiler.ReadSizeProfile
import com.bitperfect.core.utils.AppLogger

interface DriveProfiler {
    fun buildProfile(
        driveInfo: DriveInfo,
        cacheProbeResult: CacheProbeResult?,
        streamingAnalysisResult: StreamingAnalysisResult?,
        readSizeProfile: ReadSizeProfile?
    ): DriveProfile
}

class DefaultDriveProfiler : DriveProfiler {
    override fun buildProfile(
        driveInfo: DriveInfo,
        cacheProbeResult: CacheProbeResult?,
        streamingAnalysisResult: StreamingAnalysisResult?,
        readSizeProfile: ReadSizeProfile?
    ): DriveProfile {

        // 1. likelyCachesAudio
        val likelyCachesAudio = cacheProbeResult != null &&
            (cacheProbeResult.status == CacheStatus.CACHE_SUSPECTED || cacheProbeResult.status == CacheStatus.CACHE_CONFIRMED)

        // 2. supportsStreaming
        val supportsStreaming = streamingAnalysisResult != null &&
            streamingAnalysisResult.classification == StreamingClassification.STABLE_STREAMING

        // 3. unstableSeeking
        // simple heuristic: if postSeekDegradationScore is > 0 or latency metrics show seeking is problematic.
        // Let's rely on streamingAnalysisResult's postSeekDegradationScore > 0.5f for now.
        val unstableSeeking = streamingAnalysisResult != null && streamingAnalysisResult.metrics.postSeekDegradationScore > 0.5f

        // 4. stableLargeReads
        val stableLargeReads = readSizeProfile != null && readSizeProfile.maxReliableReadSize > 16 // using > 16 frames as a safe marker for 'large' reads

        val preferredReadSize = readSizeProfile?.preferredReadSize ?: 26 // fallback to standard
        val maxReliableReadSize = readSizeProfile?.maxReliableReadSize ?: 26

        // Calculate simple telemetry ratios
        var retrySuccessRate = 0f
        var overlapInstabilityRate = 0f

        if (readSizeProfile != null && readSizeProfile.metrics.isNotEmpty()) {
            var totalAttempts = 0
            var totalOverlapFailures = 0
            var totalRereadEscalations = 0
            var totalSuccesses = 0

            for (metric in readSizeProfile.metrics) {
                totalAttempts += metric.attempts
                totalOverlapFailures += metric.overlapFailures
                totalRereadEscalations += metric.rereadEscalations
                totalSuccesses += metric.successfulReads
            }

            // Simple heuristic for overlap instability: overlap failures / total verifications (where total verification attempts is totalAttempts)
            if (totalAttempts > 0) {
                overlapInstabilityRate = totalOverlapFailures.toFloat() / totalAttempts.toFloat()
            }

            // If telemetry is insufficient for exact recovery counting, default conservatively.
            // As discussed, keep it intentionally simple.
            retrySuccessRate = 0f
        }

        val profile = DriveProfile(
            vendor = driveInfo.vendor,
            model = driveInfo.model,
            firmware = driveInfo.firmware,
            preferredReadSize = preferredReadSize,
            maxReliableReadSize = maxReliableReadSize,
            supportsStreaming = supportsStreaming,
            likelyCachesAudio = likelyCachesAudio,
            stableLargeReads = stableLargeReads,
            unstableSeeking = unstableSeeking,
            retrySuccessRate = retrySuccessRate,
            overlapInstabilityRate = overlapInstabilityRate,
            profileVersion = 1
        )

        AppLogger.d(TAG, "Drive profile created\n" +
            "vendor=${profile.vendor}\n" +
            "model=${profile.model}\n" +
            "preferredReadSize=${profile.preferredReadSize}\n" +
            "supportsStreaming=${profile.supportsStreaming}\n" +
            "likelyCachesAudio=${profile.likelyCachesAudio}")

        return profile
    }

    companion object {
        private const val TAG = "DriveProfiler"
    }
}
