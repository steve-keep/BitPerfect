package com.bitperfect.app.ripping.paranoia

import com.bitperfect.core.utils.AppLogger
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryStrategy
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryMetadata
import com.bitperfect.app.ripping.paranoia.cache.ReadAttempt
import com.bitperfect.core.utils.MonotonicClock
import com.bitperfect.core.utils.DefaultMonotonicClock
import java.util.zip.CRC32

class RereadEngine(
    private val verifier: OverlapVerifier,
    private val maxRereads: Int = 6,
    private val clock: MonotonicClock = DefaultMonotonicClock()
) {

    suspend fun executeAttempts(
        context: com.bitperfect.app.ripping.paranoia.strategy.RecoveryContext,
        strategy: RecoveryStrategy,
        previousVerifiedChunk: VerifiedChunk,
        failedChunk: VerifiedChunk,
        candidateOverlaps: MutableList<ByteArray>,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): RereadExecutionResult {
        var lastAttempt = failedChunk
        val window = strategy.getRecoveryWindow(failedChunk)
        var previousRereadCandidate: VerifiedChunk? = null
        var finalDriftEvent: DriftEvent? = null

        val readAttempts = mutableListOf<ReadAttempt>()

        for (attempt in 1..maxRereads) {
            AppLogger.d("RereadEngine", "reread_attempt strategy=\${strategy.strategyName} lba=\${failedChunk.startLba} attempt=\$attempt")

            val startMs = clock.nowMs()
            val currentAttempt = strategy.performAttempt(context.copy(rereadAttempt = attempt), failedChunk, readChunk)
            val durationMs = clock.nowMs() - startMs

            if (currentAttempt != null) {
                val matchedPrevious = previousRereadCandidate != null && isStableCandidate(previousRereadCandidate, currentAttempt)

                val crc = CRC32()
                crc.update(currentAttempt.pcm)

                readAttempts.add(
                    ReadAttempt(
                        startLba = currentAttempt.startLba,
                        endLba = currentAttempt.endLba,
                        durationMs = durationMs.toDouble(),
                        wasReread = true,
                        matchedPreviousAttempt = matchedPrevious,
                        mismatchRegions = emptyList(), // Not calculating exact mismatch bounds for performance yet
                        checksum = crc.value
                    )
                )

                // Accumulate overlap candidate for US-003 multi-pass comparison
                candidateOverlaps.add(currentAttempt.overlapHead)

                // 1. Verify overlap against previous verified chunk
                val overlapsProperly = verifier.verifyOverlap(
                    tail = previousVerifiedChunk.overlapTail,
                    head = currentAttempt.overlapHead
                )

                if (overlapsProperly) {
                    AppLogger.d("RereadEngine", "reread_match overlap check passed for strategy=\${strategy.strategyName} attempt=\$attempt lba=\${failedChunk.startLba}")
                } else {
                    AppLogger.d("RereadEngine", "reread_mismatch overlap check failed for strategy=\${strategy.strategyName} attempt=\$attempt lba=\${failedChunk.startLba}")
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
                     AppLogger.d("RereadEngine", "reread_recovered strategy=\${strategy.strategyName} lba=\${failedChunk.startLba} overlapStartLba=\${previousVerifiedChunk.endLba - (verifier.overlapSizeBytes / 2352)} confidence=HIGH")
                     val successAttempt = currentAttempt.copy(rereadCount = attempt)
                     val metadata = RecoveryMetadata(strategy.strategyName, window.startLba, window.startLba + window.sectorCount, attempt, true)

                     return RereadExecutionResult.Recovered(successAttempt, metadata, readAttempts)
                }

                lastAttempt = currentAttempt
                previousRereadCandidate = currentAttempt
            } else {
                AppLogger.w("RereadEngine", "reread_attempt failed to read data strategy=\${strategy.strategyName} lba=\${failedChunk.startLba} attempt=\$attempt")
            }
        }

        // If this strategy failed after all retries
        val metadata = RecoveryMetadata(strategy.strategyName, window.startLba, window.startLba + window.sectorCount, maxRereads, false, finalDriftEvent)
        return RereadExecutionResult.Failed(lastAttempt.copy(rereadCount = maxRereads), metadata, readAttempts)
    }

    private fun isStableCandidate(
        previousAttempt: VerifiedChunk,
        currentAttempt: VerifiedChunk
    ): Boolean {
        if (previousAttempt.pcm.size != currentAttempt.pcm.size) return false
        return previousAttempt.pcm.contentEquals(currentAttempt.pcm)
    }
}
