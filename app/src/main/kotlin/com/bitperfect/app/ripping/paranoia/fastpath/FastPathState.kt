package com.bitperfect.app.ripping.paranoia.fastpath

data class FastPathState(
    val stableChunkCount: Int = 0,
    val consecutiveImmediateMatches: Int = 0,
    val hasRecentAnomalies: Boolean = false,
    val eligible: Boolean = false
)
