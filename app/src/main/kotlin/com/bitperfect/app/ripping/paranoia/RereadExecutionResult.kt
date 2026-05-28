package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.strategy.RecoveryMetadata

sealed class RereadExecutionResult {
    abstract val chunk: VerifiedChunk
    abstract val metadata: RecoveryMetadata

    data class Recovered(
        override val chunk: VerifiedChunk,
        override val metadata: RecoveryMetadata
    ) : RereadExecutionResult()

    data class Failed(
        override val chunk: VerifiedChunk,
        override val metadata: RecoveryMetadata
    ) : RereadExecutionResult()
}
