package com.bitperfect.app.ripping.paranoia

class RipConfidenceEvaluator {
    fun evaluateChunkConfidence(
        overlapMatchedImmediately: Boolean,
        rereadsPerformed: Int,
        recoverySucceeded: Boolean
    ): RipConfidence {
        return if (overlapMatchedImmediately && rereadsPerformed == 0) {
            RipConfidence.HIGH
        } else if (recoverySucceeded) {
            RipConfidence.MEDIUM
        } else {
            RipConfidence.LOW
        }
    }

    fun aggregateTrackConfidence(
        current: RipConfidence,
        incoming: RipConfidence
    ): RipConfidence {
        return if (incoming.ordinal > current.ordinal) incoming else current
    }
}
