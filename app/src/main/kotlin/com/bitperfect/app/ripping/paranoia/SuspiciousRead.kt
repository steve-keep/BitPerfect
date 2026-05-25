package com.bitperfect.app.ripping.paranoia

data class SuspiciousRead(
    val startLba: Int,
    val endLba: Int,
    val recoveryWindowStartLba: Int?,
    val recoveryWindowEndLba: Int?,
    val strategy: String?,
    val rereadAttempts: Int,
    val recovered: Boolean
)
