package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.anomaly.AlignmentAnomaly

class RipConfidenceEvaluator {
    fun evaluateChunkConfidence(
        overlapMatchedImmediately: Boolean,
        rereadsPerformed: Int,
        recoverySucceeded: Boolean,
        anomaly: AlignmentAnomaly? = null
    ): RipConfidence {
        if (overlapMatchedImmediately && rereadsPerformed == 0) {
            return RipConfidence.HIGH
        }

        if (recoverySucceeded) {
            return RipConfidence.MEDIUM
        }

        return when (anomaly) {
            is AlignmentAnomaly.PossibleShift -> RipConfidence.MEDIUM
            is AlignmentAnomaly.SevereInstability -> RipConfidence.LOW
            is AlignmentAnomaly.None -> RipConfidence.LOW // Failed but no anomaly? fallback to low.
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
