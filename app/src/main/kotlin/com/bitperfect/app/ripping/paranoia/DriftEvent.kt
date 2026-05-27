package com.bitperfect.app.ripping.paranoia

enum class DriftConfidence {
    LOW,
    MEDIUM,
    HIGH
}

data class DriftEvent(
    val expectedOffset: Int,
    val observedOffset: Int,
    val shiftSamples: Int,
    val confidence: DriftConfidence,
    val overlapMatchLength: Int
)
