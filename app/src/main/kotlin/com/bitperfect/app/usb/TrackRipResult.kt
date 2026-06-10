package com.bitperfect.app.usb

import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.paranoia.SuspiciousRead
import com.bitperfect.app.ripping.streaming.SequentialReadTelemetry

data class TrackRipStats(
    val chunksRead: Int,
    val overlapVerifications: Int,
    val overlapFailures: Int,
    val alignmentChecks: Int,
    val driftEvents: Int,
    val recoveryWindows: Int,
    val escalations: Int,
    val fastPathChunks: Int
)

sealed class TrackRipResult {
    data class Success(
        val checksumV1: Long,
        val checksumV2: Long,
        val sectorsRead: Int,
        val missingStartSectors: Int,
        val confidence: RipConfidence,
        val suspiciousRegions: List<SuspiciousRead>,
        val stats: TrackRipStats,
        val overreadBuffer: ByteArray?,
        val streamingReads: List<SequentialReadTelemetry>
    ) : TrackRipResult()

    data class Failed(val reason: String) : TrackRipResult()

    object Cancelled : TrackRipResult()
}

data class TrackVerificationResult(
    val finalStatus: RipStatus,
    val matchedVersion: Int?,
    val matchedConfidence: Int?,
    val allExpectedV1: List<Long>,
    val allExpectedV2: List<Long>,
    val hasExpected: Boolean
)

sealed class WriteTrackResult {
    object Success : WriteTrackResult()
    data class Failed(val reason: String) : WriteTrackResult()
}
