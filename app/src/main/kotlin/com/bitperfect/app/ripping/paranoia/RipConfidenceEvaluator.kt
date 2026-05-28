package com.bitperfect.app.ripping.paranoia

class RipConfidenceEvaluator {
    fun evaluateChunkConfidence(
        overlapMatchedImmediately: Boolean,
        rereadsPerformed: Int,
        recoverySucceeded: Boolean,
        driftEvent: DriftEvent? = null,
        instabilityType: InstabilityType? = null
    ): RipConfidence {
        if (overlapMatchedImmediately && rereadsPerformed == 0 && (instabilityType == null || instabilityType == InstabilityType.NONE)) {
            return RipConfidence.HIGH
        }

        if (recoverySucceeded) {
            return RipConfidence.MEDIUM
        }

        return when (driftEvent?.confidence) {
            DriftConfidence.HIGH -> RipConfidence.MEDIUM
            DriftConfidence.MEDIUM -> RipConfidence.MEDIUM
            DriftConfidence.LOW -> RipConfidence.LOW
            null -> RipConfidence.LOW
        }
    }

    fun aggregateTrackConfidence(
        current: RipConfidence,
        incoming: RipConfidence
    ): RipConfidence {
        return if (incoming.ordinal > current.ordinal) incoming else current
    }

    fun evaluateAlignmentConfidence(
        current: RipConfidence,
        validation: AlignmentValidationResult
    ): RipConfidence {
        if (validation.valid) return current

        var hasCriticalIssue = false
        var hasMinorIssue = false

        for (anomaly in validation.anomalies) {
            when (anomaly) {
                is AlignmentIssue.DuplicateSamples,
                is AlignmentIssue.DroppedSamples,
                is AlignmentIssue.InvalidOverlapTrim -> hasCriticalIssue = true
                is AlignmentIssue.BoundaryDiscontinuity -> hasMinorIssue = true
            }
        }

        return when {
            hasCriticalIssue -> RipConfidence.LOW
            hasMinorIssue && current == RipConfidence.HIGH -> RipConfidence.MEDIUM
            else -> current
        }
    }
}
