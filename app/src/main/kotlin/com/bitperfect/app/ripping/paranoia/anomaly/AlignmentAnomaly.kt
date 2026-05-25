package com.bitperfect.app.ripping.paranoia.anomaly

sealed class AlignmentAnomaly {
    data object None : AlignmentAnomaly()

    data class PossibleShift(
        val sampleDelta: Int,
        val confidence: Float
    ) : AlignmentAnomaly()

    data class SevereInstability(
        val mismatchCount: Int
    ) : AlignmentAnomaly()
}
