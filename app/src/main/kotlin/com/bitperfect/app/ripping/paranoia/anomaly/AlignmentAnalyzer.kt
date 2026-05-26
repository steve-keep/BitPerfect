package com.bitperfect.app.ripping.paranoia.anomaly

import kotlin.math.min

class AlignmentAnalyzer(
    private val maxShiftSamples: Int = 16,
    private val confidenceThreshold: Float = 0.98f
) {
    fun analyze(
        expectedOverlap: ByteArray,
        actualOverlap: ByteArray
    ): AlignmentAnomaly {
        // PCM arrays must be even (16-bit samples)
        require(expectedOverlap.size % 2 == 0) { "expectedOverlap must contain 16-bit samples" }
        require(actualOverlap.size % 2 == 0) { "actualOverlap must contain 16-bit samples" }

        val expectedSamples = expectedOverlap.size / 2
        val actualSamples = actualOverlap.size / 2

        var bestShift = 0
        var bestMatchCount = -1
        var minMismatchCount = Int.MAX_VALUE
        var bestComparedCount = 0

        for (shift in -maxShiftSamples..maxShiftSamples) {
            var matchCount = 0
            var mismatchCount = 0

            val startExpected = if (shift > 0) shift else 0
            val startActual = if (shift < 0) -shift else 0

            val comparisonLength = min(expectedSamples - startExpected, actualSamples - startActual)
            if (comparisonLength <= 0) continue

            for (i in 0 until comparisonLength) {
                val expIdx = (startExpected + i) * 2
                val actIdx = (startActual + i) * 2

                if (expectedOverlap[expIdx] == actualOverlap[actIdx] &&
                    expectedOverlap[expIdx + 1] == actualOverlap[actIdx + 1]) {
                    matchCount++
                } else {
                    mismatchCount++
                }
            }

            if (mismatchCount < minMismatchCount) {
                minMismatchCount = mismatchCount
            }

            if (matchCount > bestMatchCount) {
                bestMatchCount = matchCount
                bestShift = shift
                bestComparedCount = comparisonLength
            }
        }

        if (bestComparedCount == 0) {
            return AlignmentAnomaly.SevereInstability(minMismatchCount)
        }

        val confidence = bestMatchCount.toFloat() / bestComparedCount.toFloat()

        return if (confidence >= confidenceThreshold) {
            if (bestShift == 0 && confidence == 1.0f) {
                AlignmentAnomaly.None
            } else {
                AlignmentAnomaly.PossibleShift(bestShift, confidence)
            }
        } else {
            AlignmentAnomaly.SevereInstability(minMismatchCount)
        }
    }
}
