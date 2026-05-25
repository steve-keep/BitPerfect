package com.bitperfect.app.ripping.paranoia

import com.bitperfect.core.utils.AppLogger

class RereadEngine(
    private val verifier: OverlapVerifier,
    private val maxRereads: Int = 6
) {

    suspend fun recover(
        previousVerifiedChunk: VerifiedChunk,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): RereadRecoveryResult {
        var lastAttempt = failedChunk
        var previousRereadCandidate: VerifiedChunk? = null

        val sectorsToRead = failedChunk.endLba - failedChunk.startLba

        AppLogger.w("RereadEngine", "suspicious_region lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)}")

        for (attempt in 1..maxRereads) {
            AppLogger.d("RereadEngine", "reread_attempt lba=${failedChunk.startLba} attempt=$attempt")

            val currentAttempt = readChunk(failedChunk.startLba, sectorsToRead)
            if (currentAttempt != null) {

                // 1. Verify overlap against previous verified chunk
                val overlapsProperly = verifier.verifyOverlap(
                    tail = previousVerifiedChunk.overlapTail,
                    head = currentAttempt.overlapHead
                )

                if (overlapsProperly) {
                    AppLogger.d("RereadEngine", "reread_match overlap check passed for attempt=$attempt lba=${failedChunk.startLba}")
                } else {
                    AppLogger.d("RereadEngine", "reread_mismatch overlap check failed for attempt=$attempt lba=${failedChunk.startLba}")
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
                     AppLogger.d("RereadEngine", "reread_recovered lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)} confidence=HIGH")
                     return RereadRecoveryResult.Recovered(currentAttempt.copy(rereadCount = attempt))
                }

                lastAttempt = currentAttempt
                previousRereadCandidate = currentAttempt
            } else {
                AppLogger.w("RereadEngine", "reread_attempt failed to read data lba=${failedChunk.startLba} attempt=$attempt")
            }
        }

        AppLogger.w("RereadEngine", "reread_failed lba=${failedChunk.startLba} overlapStartLba=${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)} confidence=LOW")
        return RereadRecoveryResult.Failed(lastAttempt.copy(rereadCount = maxRereads))
    }

    private fun isStableCandidate(
        previousAttempt: VerifiedChunk,
        currentAttempt: VerifiedChunk
    ): Boolean {
        if (previousAttempt.pcm.size != currentAttempt.pcm.size) return false
        return previousAttempt.pcm.contentEquals(currentAttempt.pcm)
    }
}
