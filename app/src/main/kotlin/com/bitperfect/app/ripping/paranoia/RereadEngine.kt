package com.bitperfect.app.ripping.paranoia

import com.bitperfect.core.utils.AppLogger
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryStrategy
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryMetadata


class RereadEngine(
    private val strategies: List<RecoveryStrategy>,
    private val verifier: OverlapVerifier,
    private val driftDetector: DriftDetector = DriftDetector(),
    private val maxRereads: Int = 6,
    private val multiPassComparator: MultiPassComparator = MultiPassComparator()
) {

    suspend fun recover(
        previousVerifiedChunk: VerifiedChunk,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): RereadRecoveryResult {
        var lastAttempt = failedChunk

        AppLogger.w("RereadEngine", "suspicious_region lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)}")

        val metadataHistory = mutableListOf<RecoveryMetadata>()
        val candidateOverlaps = mutableListOf<ByteArray>()

        // Add the initial failed chunk's overlap to the candidate overlaps
        candidateOverlaps.add(failedChunk.overlapHead)

        for (strategy in strategies) {
            val window = strategy.getRecoveryWindow(failedChunk)
            var previousRereadCandidate: VerifiedChunk? = null

            var finalDriftEvent: DriftEvent? = null

            for (attempt in 1..maxRereads) {
                AppLogger.d("RereadEngine", "reread_attempt strategy=${strategy.strategyName} lba=${failedChunk.startLba} attempt=$attempt")

                val currentAttempt = strategy.performAttempt(failedChunk, readChunk)
                if (currentAttempt != null) {
                    // Accumulate overlap candidate for US-003 multi-pass comparison
                    candidateOverlaps.add(currentAttempt.overlapHead)

                    // 1. Verify overlap against previous verified chunk
                    val overlapsProperly = verifier.verifyOverlap(
                        tail = previousVerifiedChunk.overlapTail,
                        head = currentAttempt.overlapHead
                    )

                    if (overlapsProperly) {
                        AppLogger.d("RereadEngine", "reread_match overlap check passed for strategy=${strategy.strategyName} attempt=$attempt lba=${failedChunk.startLba}")
                    } else {
                        AppLogger.d("RereadEngine", "reread_mismatch overlap check failed for strategy=${strategy.strategyName} attempt=$attempt lba=${failedChunk.startLba}")
                    }

                    // 2. Check stability if we have a previous attempt
                    var isStable = false
                    if (previousRereadCandidate != null) {
                        isStable = isStableCandidate(previousRereadCandidate, currentAttempt)
                    } else if (attempt == 1) {
                         // Check if attempt 1 is stable against attempt 0 (failedChunk)
                         isStable = isStableCandidate(failedChunk, currentAttempt)
                    }

                    if (overlapsProperly && isStable) {
                         AppLogger.d("RereadEngine", "reread_recovered strategy=${strategy.strategyName} lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)} confidence=HIGH")
                         val successAttempt = currentAttempt.copy(rereadCount = attempt)
                         metadataHistory.add(RecoveryMetadata(strategy.strategyName, window.startLba, window.startLba + window.sectorCount, attempt, true))

                         val history = multiPassComparator.analyze(candidateOverlaps)
                         return RereadRecoveryResult.Recovered(successAttempt, metadataHistory, history)
                    } else if (!overlapsProperly && isStable) {
                        // Rereads are internally stable, but overlap verification failed. Check for drift.
                        val driftEvent = driftDetector.analyze(
                            expectedOverlap = previousVerifiedChunk.overlapTail,
                            observedOverlap = currentAttempt.overlapHead
                        )
                        if (driftEvent != null) {
                            val matchPercentage = (driftEvent.overlapMatchLength.toFloat() / (previousVerifiedChunk.overlapTail.size / 2 - Math.abs(driftEvent.shiftSamples)) * 100).toInt()
                            AppLogger.i("RereadEngine", "[US-005] Drift detected Shift=${driftEvent.shiftSamples} samples MatchLength=${matchPercentage}% Confidence=${driftEvent.confidence}")
                        }
                        finalDriftEvent = driftEvent
                    }

                    lastAttempt = currentAttempt
                    previousRereadCandidate = currentAttempt
                } else {
                    AppLogger.w("RereadEngine", "reread_attempt failed to read data strategy=${strategy.strategyName} lba=${failedChunk.startLba} attempt=$attempt")
                }
            }

            // If this strategy failed after all retries, record it and move to the next strategy
            metadataHistory.add(RecoveryMetadata(strategy.strategyName, window.startLba, window.startLba + window.sectorCount, maxRereads, false, finalDriftEvent))
        }

        AppLogger.w("RereadEngine", "reread_failed lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)} confidence=LOW")
        val history = multiPassComparator.analyze(candidateOverlaps)
        return RereadRecoveryResult.Failed(lastAttempt.copy(rereadCount = maxRereads), metadataHistory, history)
    }

    private fun isStableCandidate(
        previousAttempt: VerifiedChunk,
        currentAttempt: VerifiedChunk
    ): Boolean {
        if (previousAttempt.pcm.size != currentAttempt.pcm.size) return false
        return previousAttempt.pcm.contentEquals(currentAttempt.pcm)
    }
}
