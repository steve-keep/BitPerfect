package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.OverlapVerifier
import com.bitperfect.app.ripping.paranoia.VerifiedChunk

class OverlapRecoveryStrategy(
    private val verifier: OverlapVerifier
) : RecoveryStrategy {

    override val strategyName = "overlap_recovery"

    override fun getRecoveryWindow(failedChunk: VerifiedChunk): RecoveryWindow {
        val overlapSectors = verifier.overlapSizeBytes / 2352
        val startLba = failedChunk.startLba
        return RecoveryWindow(startLba = startLba, sectorCount = overlapSectors)
    }

    override suspend fun performAttempt(
        context: RecoveryContext,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): VerifiedChunk? {
        val window = getRecoveryWindow(failedChunk)

        // Try reading only the overlap region
        val readAttempt = readChunk(window.startLba, window.sectorCount) ?: return null

        // If the read fails for some reason (maybe short read), return null
        if (readAttempt.pcm.size != verifier.overlapSizeBytes) {
            return null
        }

        // Create a new VerifiedChunk by combining the new overlap with the rest of the failed chunk
        val failedPcmSize = failedChunk.pcm.size
        val remainderPcmSize = failedPcmSize - verifier.overlapSizeBytes

        val newPcm = ByteArray(failedPcmSize)
        // Copy the newly read overlap PCM to the HEAD
        System.arraycopy(readAttempt.pcm, 0, newPcm, 0, verifier.overlapSizeBytes)
        // Copy the remainder of the original failed chunk to the TAIL
        System.arraycopy(failedChunk.pcm, verifier.overlapSizeBytes, newPcm, verifier.overlapSizeBytes, remainderPcmSize)

        return failedChunk.copy(
            pcm = newPcm,
            overlapHead = verifier.extractOverlapHead(newPcm),
            overlapTail = verifier.extractOverlapTail(newPcm)
        )
    }
}
