package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import com.bitperfect.core.utils.AppLogger
import kotlin.math.max

class ReducedChunkRereadStrategy(
    private val policy: ChunkReductionPolicy = ChunkReductionPolicy(),
    private val currentDepth: Int = 1
) : RecoveryStrategy {

    override val strategyName = "reduced_chunk_recovery"

    override fun getRecoveryWindow(failedChunk: VerifiedChunk): RecoveryWindow {
        val originalSize = failedChunk.endLba - failedChunk.startLba
        // Reduce size by half for each depth, bounded by minimumChunkSize
        var targetSize = originalSize
        for (i in 0 until currentDepth) {
             targetSize /= 2
        }

        val reducedSize = max(policy.minimumChunkSize, targetSize)

        return RecoveryWindow(
            startLba = failedChunk.startLba,
            sectorCount = reducedSize
        )
    }

    override suspend fun performAttempt(
        context: RecoveryContext,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): VerifiedChunk? {
        val window = getRecoveryWindow(failedChunk)
        AppLogger.d("ReducedChunkRereadStrategy", "[US-021] Reduced chunk recovery enabled. ChunkSize=\${window.sectorCount} sectors")
        return readChunk(window.startLba, window.sectorCount)
    }
}
