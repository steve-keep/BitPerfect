package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.strategy.*
import com.bitperfect.app.ripping.paranoia.cache.DriveCacheAnalyzer
import com.bitperfect.app.ripping.paranoia.cache.DefaultDriveCacheAnalyzer
import com.bitperfect.core.utils.AppLogger

class RecoveryCoordinator(
    private val rereadEngine: RereadEngine,
    private val verifier: OverlapVerifier,
    private val multiPassComparator: MultiPassComparator = MultiPassComparator(),
    private val driftDetector: DriftDetector = DriftDetector(),
    private val alignmentValidator: SampleAlignmentValidator = SampleAlignmentValidator(),
    private val cacheAnalyzer: DriveCacheAnalyzer = DefaultDriveCacheAnalyzer(),
    private val maxStrategyTransitions: Int = 4
) {

    suspend fun recover(
        previousVerifiedChunk: VerifiedChunk,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): RereadRecoveryResult {
        val originalFailedChunk = failedChunk
        var currentChunk = failedChunk
        val candidateOverlaps = mutableListOf<ByteArray>(failedChunk.overlapHead)
        val metadataHistory = mutableListOf<RecoveryMetadata>()

        var currentDepth = 0
        val previousStrategies = mutableListOf<String>()

        // Initial strategy based on the mismatch
        var strategy: RecoveryStrategy = OverlapRecoveryStrategy(verifier)

        for (transition in 0 until maxStrategyTransitions) {
            AppLogger.d("RecoveryCoordinator", "[US-021] Escalation triggered. Strategy=\${strategy.strategyName} Depth=\$currentDepth")

            val context = RecoveryContext(
                trackNumber = 0,
                chunkStartLba = originalFailedChunk.startLba,
                chunkEndLba = originalFailedChunk.endLba,
                rereadAttempt = 0,
                candidateHistory = multiPassComparator.analyze(candidateOverlaps),
                driftEvent = driftDetector.analyze(previousVerifiedChunk.overlapTail, currentChunk.overlapHead),
                previousConfidence = RipConfidence.HIGH
            )

            val result = rereadEngine.executeAttempts(
                context = context,
                strategy = strategy,
                previousVerifiedChunk = previousVerifiedChunk,
                failedChunk = originalFailedChunk,
                candidateOverlaps = candidateOverlaps,
                readChunk = readChunk
            )

            val cacheResult = cacheAnalyzer.analyze(result.readAttempts)
            val updatedMetadata = result.metadata.copy(cacheProbeResult = cacheResult)

            metadataHistory.add(updatedMetadata)
            previousStrategies.add(strategy.strategyName)
            currentChunk = result.chunk

            if (result is RereadExecutionResult.Recovered) {
                AppLogger.d("RecoveryCoordinator", "[US-021] Recovery successful with \${strategy.strategyName}")
                val history = multiPassComparator.analyze(candidateOverlaps)
                return RereadRecoveryResult.Recovered(result.chunk, metadataHistory, history)
            }

            // Evidence Evaluation Phase
            currentDepth++

            val comparisonHistory = multiPassComparator.analyze(candidateOverlaps)
            val driftEvent = driftDetector.analyze(
                expectedOverlap = previousVerifiedChunk.overlapTail,
                observedOverlap = currentChunk.overlapHead
            )

            if (driftEvent != null) {
                AppLogger.i("RecoveryCoordinator", "[US-021] Drift detected. Shift=\${driftEvent.shiftSamples} samples. Confidence=\${driftEvent.confidence}")
            }

            val escalationState = EscalationState(
                escalationDepth = currentDepth,
                previousStrategies = previousStrategies,
                candidateHistory = comparisonHistory,
                driftEvent = driftEvent,
                validationResult = null // Validated separately upon success
            )

            strategy = selectNextStrategy(escalationState, verifier)


        }

        val history = multiPassComparator.analyze(candidateOverlaps)
        return RereadRecoveryResult.Failed(currentChunk, metadataHistory, history)
    }

    private fun selectNextStrategy(evidence: EscalationState, verifier: OverlapVerifier): RecoveryStrategy {
        val driftEvent = evidence.driftEvent
        val history = evidence.candidateHistory
        val depth = evidence.escalationDepth

        if (driftEvent != null && driftEvent.confidence != DriftConfidence.LOW) {
            AppLogger.d("RecoveryCoordinator", "[US-021] Reason=repeated drift events. Escalating to DriftFocusedRereadStrategy.")
            return DriftFocusedRereadStrategy(verifier.overlapSizeBytes / 2352)
        }

        if (history != null) {
            when (history.instabilityType) {
                InstabilityType.TRANSIENT_MISMATCH -> {
                    // This shouldn't happen here as it would have recovered, but for completeness
                    return OverlapRecoveryStrategy(verifier)
                }
                InstabilityType.STABLE_CONVERGENCE -> {
                    AppLogger.d("RecoveryCoordinator", "[US-021] Reason=STABLE_CONVERGENCE. Escalating to FullChunkRereadStrategy.")
                    return FullChunkRecoveryStrategy()
                }
                InstabilityType.OSCILLATING_MISMATCH -> {
                    AppLogger.d("RecoveryCoordinator", "[US-021] Reason=OSCILLATING_MISMATCH. Escalating to ReducedChunkRereadStrategy.")
                    return ReducedChunkRereadStrategy(currentDepth = depth)
                }
                InstabilityType.PERSISTENT_INSTABILITY -> {
                    AppLogger.d("RecoveryCoordinator", "[US-021] Reason=persistent instability. Escalating to ReducedChunkRereadStrategy.")
                    return ReducedChunkRereadStrategy(currentDepth = depth)
                }
                InstabilityType.NONE -> {
                    AppLogger.d("RecoveryCoordinator", "[US-021] Reason=unknown instability. Escalating to FullChunkRereadStrategy.")
                    return FullChunkRecoveryStrategy()
                }
            }
        }

        // Fallback escalation
        AppLogger.d("RecoveryCoordinator", "[US-021] Reason=unknown instability. Escalating to FullChunkRereadStrategy.")
        return FullChunkRecoveryStrategy()
    }
}
