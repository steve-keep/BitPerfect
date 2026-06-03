package com.bitperfect.app.ripping.logging

import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.capability.DriveProfile
import com.bitperfect.app.ripping.streaming.StreamingClassification
import com.bitperfect.app.ripping.paranoia.cache.CacheProbeResult
import com.bitperfect.app.ripping.streaming.StreamingAnalysisResult
import com.bitperfect.app.ripping.profiler.ReadSizeProfile
import com.bitperfect.app.ripping.paranoia.cache.CacheStatus
import com.bitperfect.app.usb.RipStatus

enum class RipMode {
    SECURE
}

sealed interface RipLogEvent {

    data class SessionStarted(
        val appVersion: String,
        val deviceModel: String,
        val androidVersion: String,
        val timestampIso: String,
        val mode: RipMode,
        val chunkSize: Int,
        val overlapSize: Int,
        val driveVendor: String,
        val driveModel: String,
        val driveFirmware: String?,
        val albumTitle: String,
        val artistName: String
    ) : RipLogEvent

    data class DriveAnalysisCompleted(
        val profile: DriveProfile,
        val cacheProbeResult: CacheProbeResult?,
        val streamingAnalysisResult: StreamingAnalysisResult?,
        val readSizeProfile: ReadSizeProfile?
    ) : RipLogEvent

    data class TrackStarted(
        val trackNumber: Int,
        val title: String
    ) : RipLogEvent

    data class TransportAnomaly(
        val trackNumber: Int,
        val anomalyType: String,
        val details: String
    ) : RipLogEvent

    data class OverlapMismatchDetected(
        val trackNumber: Int,
        val lbaStart: Int,
        val lbaEnd: Int
    ) : RipLogEvent

    data class RereadEscalated(
        val trackNumber: Int,
        val lbaStart: Int,
        val lbaEnd: Int,
        val strategy: String
    ) : RipLogEvent

    data class RecoverySucceeded(
        val trackNumber: Int,
        val lbaStart: Int,
        val lbaEnd: Int,
        val rereadAttempts: Int
    ) : RipLogEvent

    data class RecoveryFailed(
        val trackNumber: Int,
        val lbaStart: Int,
        val lbaEnd: Int,
        val rereadAttempts: Int
    ) : RipLogEvent

    data class TrackCompleted(
        val trackNumber: Int,
        val confidence: RipConfidence,
        val rereads: Int,
        val suspiciousReads: Int,
        val status: RipStatus,
        val accurateRipStatus: String,
        val computedChecksumV1: Long? = null,
        val computedChecksumV2: Long? = null,
        val expectedChecksumsV1: List<Long> = emptyList(),
        val expectedChecksumsV2: List<Long> = emptyList(),
        val startLba: Int,
        val endLba: Int,
        val totalSectors: Int,
        val sectorsRead: Int,
        val durationSeconds: Double,
        val summary: TrackRipSummary
    ) : RipLogEvent


    data class TrackRipSummary(
        val chunksRead: Int,
        val overlapVerifications: Int,
        val overlapFailures: Int,
        val alignmentChecks: Int,
        val driftEvents: Int,
        val recoveryWindows: Int,
        val escalations: Int,
        val fastPathChunks: Int
    )

    data class SessionCompleted(
        val success: Boolean,
        val matchedDiscId1: Long? = null,
        val matchedDiscId2: Long? = null
    ) : RipLogEvent

    data class FastPathStateChanged(
        val trackNumber: Int,
        val enabled: Boolean,
        val reason: String? = null
    ) : RipLogEvent

    data class SampleAlignmentValidated(
        val trackNumber: Int,
        val valid: Boolean,
        val anomalyType: String? = null,
        val expectedTrim: Int? = null,
        val actualTrim: Int? = null,
        val sampleCount: Int? = null
    ) : RipLogEvent

    data class ReadDriftDetected(
        val trackNumber: Int,
        val lbaStart: Int,
        val shiftSamples: Int,
        val confidence: String
    ) : RipLogEvent

    data class MultiPassComparisonCompleted(
        val trackNumber: Int,
        val lbaStart: Int,
        val totalAttempts: Int,
        val uniqueCandidates: Int,
        val instabilityType: String,
        val resolved: Boolean
    ) : RipLogEvent

    data class ReadConsistencyScored(
        val trackNumber: Int,
        val lbaStart: Int,
        val score: Float
    ) : RipLogEvent

    data class TargetedSectorRecoveryLogged(
        val trackNumber: Int,
        val lbaStart: Int,
        val sectorCount: Int,
        val strategy: String
    ) : RipLogEvent
}
