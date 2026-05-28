package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk

interface RecoveryStrategy {
    val strategyName: String

    fun getRecoveryWindow(failedChunk: VerifiedChunk): RecoveryWindow

    suspend fun performAttempt(
        context: RecoveryContext,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): VerifiedChunk?
}
