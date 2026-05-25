package com.bitperfect.app.ripping.paranoia

data class SuspiciousRead(
    val startLba: Int,
    val endLba: Int,
    val rereadAttempts: Int,
    val recovered: Boolean
)
