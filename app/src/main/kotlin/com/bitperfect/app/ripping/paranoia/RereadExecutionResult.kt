package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.strategy.RecoveryMetadata
import com.bitperfect.app.ripping.paranoia.cache.ReadAttempt

sealed class RereadExecutionResult {
    abstract val chunk: VerifiedChunk
    abstract val metadata: RecoveryMetadata
    abstract val readAttempts: List<ReadAttempt>

    data class Recovered(
        override val chunk: VerifiedChunk,
        override val metadata: RecoveryMetadata,
        override val readAttempts: List<ReadAttempt> = emptyList()
    ) : RereadExecutionResult()

    data class Failed(
        override val chunk: VerifiedChunk,
        override val metadata: RecoveryMetadata,
        override val readAttempts: List<ReadAttempt> = emptyList()
    ) : RereadExecutionResult()
}
