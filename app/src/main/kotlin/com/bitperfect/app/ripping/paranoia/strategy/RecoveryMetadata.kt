package com.bitperfect.app.ripping.paranoia.strategy


import com.bitperfect.app.ripping.paranoia.DriftEvent

data class RecoveryMetadata(
    val strategy: String,
    val recoveryWindowStartLba: Int,
    val recoveryWindowEndLba: Int,
    val rereadAttempts: Int,
    val recovered: Boolean,

    val driftEvent: DriftEvent? = null
)
