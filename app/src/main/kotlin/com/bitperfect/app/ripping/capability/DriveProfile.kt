package com.bitperfect.app.ripping.capability

data class DriveProfile(
    val vendor: String,
    val model: String,
    val firmware: String?,

    val preferredReadSize: Int,
    val maxReliableReadSize: Int,

    val supportsStreaming: Boolean,
    val likelyCachesAudio: Boolean,
    val stableLargeReads: Boolean,
    val unstableSeeking: Boolean,

    val retrySuccessRate: Float,
    val overlapInstabilityRate: Float,

    val profileVersion: Int = 1
)
