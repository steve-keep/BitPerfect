package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk

class FullChunkRecoveryStrategy : RecoveryStrategy {

    override val strategyName = "full_chunk_recovery"

    override fun getRecoveryWindow(failedChunk: VerifiedChunk): RecoveryWindow {
        return RecoveryWindow(
            startLba = failedChunk.startLba,
            sectorCount = failedChunk.endLba - failedChunk.startLba
        )
    }

    override suspend fun performAttempt(
        context: RecoveryContext,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): VerifiedChunk? {
        val window = getRecoveryWindow(failedChunk)
        return readChunk(window.startLba, window.sectorCount)
    }
}
