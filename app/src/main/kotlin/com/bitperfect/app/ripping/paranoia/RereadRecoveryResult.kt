package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.strategy.RecoveryMetadata

sealed class RereadRecoveryResult {
    data class Recovered(
        val chunk: VerifiedChunk,
        val metadataHistory: List<RecoveryMetadata>
    ) : RereadRecoveryResult()

    data class Failed(
        val chunk: VerifiedChunk,
        val metadataHistory: List<RecoveryMetadata>
    ) : RereadRecoveryResult()
}
