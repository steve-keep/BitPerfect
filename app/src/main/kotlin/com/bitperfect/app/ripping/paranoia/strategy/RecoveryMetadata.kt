package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.anomaly.AlignmentAnomaly

data class RecoveryMetadata(
    val strategy: String,
    val recoveryWindowStartLba: Int,
    val recoveryWindowEndLba: Int,
    val rereadAttempts: Int,
    val recovered: Boolean,
    val anomaly: AlignmentAnomaly? = null
)
