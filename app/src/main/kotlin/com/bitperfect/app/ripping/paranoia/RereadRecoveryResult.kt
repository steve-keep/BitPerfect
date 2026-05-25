package com.bitperfect.app.ripping.paranoia

sealed class RereadRecoveryResult {
    data class Recovered(val chunk: VerifiedChunk) : RereadRecoveryResult()
    data class Failed(val chunk: VerifiedChunk) : RereadRecoveryResult()
}
