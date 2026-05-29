package com.bitperfect.app.ripping.logging

import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.capability.DriveProfile
import com.bitperfect.app.ripping.streaming.StreamingClassification
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
        val cacheStatus: CacheStatus?,
        val streamingClassification: StreamingClassification?,
        val preferredReadSize: Int?
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
        val durationSeconds: Double
    ) : RipLogEvent

    data class SessionCompleted(
        val success: Boolean
    ) : RipLogEvent
}
