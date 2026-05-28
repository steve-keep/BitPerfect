package com.bitperfect.app.ripping.paranoia.fastpath

import com.bitperfect.core.utils.AppLogger
import com.bitperfect.app.ripping.paranoia.RipConfidence

class FastPathEvaluator {
    var state = FastPathState()
        private set

    fun reportMatch(confidence: RipConfidence = RipConfidence.HIGH) {
        if (confidence != RipConfidence.HIGH) {
            reportConfidenceDowngrade()
            return
        }

        val newMatches = state.consecutiveImmediateMatches + 1
        val newStableCount = state.stableChunkCount + 1

        val newEligible = !state.hasRecentAnomalies && newMatches >= 3

        val wasEligible = state.eligible

        state = state.copy(
            stableChunkCount = newStableCount,
            consecutiveImmediateMatches = newMatches,
            eligible = newEligible
        )

        if (!wasEligible && newEligible) {
            AppLogger.d("FastPathEvaluator", "[US-022] Fast-path enabled")
            AppLogger.d("FastPathEvaluator", "[US-022] StableChunkCount=$newStableCount")
        }
    }

    fun reportMismatch() {
        revoke("OverlapMismatch")
    }

    fun reportAnomaly() {
        revoke("AlignmentAnomaly")
    }

    fun reportConfidenceDowngrade() {
        revoke("ConfidenceDowngrade")
    }

    private fun revoke(reason: String) {
        if (state.eligible) {
            AppLogger.d("FastPathEvaluator", "[US-022] Fast-path revoked")
            AppLogger.d("FastPathEvaluator", "[US-022] Reason=$reason")
        }
        state = state.copy(
            consecutiveImmediateMatches = 0,
            hasRecentAnomalies = true,
            eligible = false
        )
    }
}
