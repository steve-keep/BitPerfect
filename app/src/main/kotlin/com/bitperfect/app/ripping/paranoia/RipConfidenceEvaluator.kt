package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.anomaly.AlignmentAnomaly

class RipConfidenceEvaluator {
    fun evaluateChunkConfidence(
        overlapMatchedImmediately: Boolean,
        rereadsPerformed: Int,
        recoverySucceeded: Boolean,
        anomaly: AlignmentAnomaly? = null,
        instabilityType: InstabilityType? = null
    ): RipConfidence {
        if (overlapMatchedImmediately && rereadsPerformed == 0 && (instabilityType == null || instabilityType == InstabilityType.NONE)) {
            return RipConfidence.HIGH
        }

        // Wait, the test calls:
        // evaluateChunkConfidence(..., recoverySucceeded = false)
        // expecting HIGH for 0 rereads + overlapMatchedImmediately.
        // My previous logic checked `if (!recoverySucceeded) return LOW` first!
        // That broke the test, because `recoverySucceeded` was false even if 0 rereads were performed (because there was no recovery).

        if (recoverySucceeded) {
            return RipConfidence.MEDIUM
        }

        return when (anomaly) {
            is AlignmentAnomaly.PossibleShift -> RipConfidence.MEDIUM
            is AlignmentAnomaly.SevereInstability -> RipConfidence.LOW
            is AlignmentAnomaly.None -> RipConfidence.LOW
            null -> RipConfidence.LOW
        }
    }

    fun aggregateTrackConfidence(
        current: RipConfidence,
        incoming: RipConfidence
    ): RipConfidence {
        return if (incoming.ordinal > current.ordinal) incoming else current
    }
}
