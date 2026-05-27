package com.bitperfect.app.ripping.paranoia

import kotlin.math.min

class DriftDetector(
    private val maxShiftSamples: Int = 12
) {
    fun analyze(
        expectedOverlap: ByteArray,
        observedOverlap: ByteArray
    ): DriftEvent? {
        // PCM arrays must be even (16-bit samples)
        require(expectedOverlap.size % 2 == 0) { "expectedOverlap must contain 16-bit samples" }
        require(observedOverlap.size % 2 == 0) { "observedOverlap must contain 16-bit samples" }

        val expectedSamples = expectedOverlap.size / 2
        val observedSamples = observedOverlap.size / 2

        var bestShift = 0
        var bestContiguousMatchCount = -1
        var bestComparedCount = 0

        for (shift in -maxShiftSamples..maxShiftSamples) {
            // A positive shift means observed is delayed relative to expected
            // e.g. expected = ABC, observed = __ABC. Then expected[0] == observed[2].
            // So for a positive shift, startExpected = 0, startObserved = shift
            val startExpected = if (shift < 0) -shift else 0
            val startObserved = if (shift > 0) shift else 0

            val comparisonLength = min(expectedSamples - startExpected, observedSamples - startObserved)
            if (comparisonLength <= 0) continue

            var currentContiguousMatch = 0
            var maxContiguousMatch = 0

            for (i in 0 until comparisonLength) {
                val expIdx = (startExpected + i) * 2
                val actIdx = (startObserved + i) * 2

                if (expectedOverlap[expIdx] == observedOverlap[actIdx] &&
                    expectedOverlap[expIdx + 1] == observedOverlap[actIdx + 1]
                ) {
                    currentContiguousMatch++
                    if (currentContiguousMatch > maxContiguousMatch) {
                        maxContiguousMatch = currentContiguousMatch
                    }
                } else {
                    currentContiguousMatch = 0
                }
            }

            if (maxContiguousMatch > bestContiguousMatchCount) {
                bestContiguousMatchCount = maxContiguousMatch
                bestShift = shift
                bestComparedCount = comparisonLength
            }
        }

        if (bestShift == 0 || bestComparedCount == 0) {
            return null
        }

        val matchPercent = bestContiguousMatchCount.toFloat() / bestComparedCount.toFloat()

        val confidence = when {
            matchPercent >= 0.98f -> DriftConfidence.HIGH
            matchPercent >= 0.90f -> DriftConfidence.MEDIUM
            matchPercent >= 0.75f -> DriftConfidence.LOW
            else -> return null
        }

        val startExpected = if (bestShift < 0) -bestShift else 0
        val startObserved = if (bestShift > 0) bestShift else 0

        return DriftEvent(
            expectedOffset = startExpected,
            observedOffset = startObserved,
            shiftSamples = bestShift,
            confidence = confidence,
            overlapMatchLength = bestContiguousMatchCount
        )
    }
}
