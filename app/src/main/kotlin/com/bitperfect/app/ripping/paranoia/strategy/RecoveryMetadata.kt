package com.bitperfect.app.ripping.paranoia.strategy

data class RecoveryMetadata(
    val strategy: String,
    val recoveryWindowStartLba: Int,
    val recoveryWindowEndLba: Int,
    val rereadAttempts: Int,
    val recovered: Boolean
)
