package com.bitperfect.app.ripping.paranoia

import com.bitperfect.core.utils.AppLogger

class SampleAlignmentValidator {

    fun validateBoundary(
        previousCommittedPcm: ByteArray,
        nextCommittedPcm: ByteArray,
        expectedTrimSamples: Int,
        actualTrimSamples: Int
    ): AlignmentValidationResult {
        val anomalies = mutableListOf<AlignmentIssue>()

        if (expectedTrimSamples != actualTrimSamples) {
            anomalies.add(AlignmentIssue.InvalidOverlapTrim(expectedTrimSamples, actualTrimSamples))
        }

        // Search for Duplicate Samples or Dropped Samples in a boundary region
        // Duplicate means there is a repeated sequence.
        // If we look at the last N samples of previous and first N of next.

        // This logic requires a more precise definition for Dropped/Duplicate based on pure bytes.
        // We know PCM is 16-bit stereo = 4 bytes per sample.

        val prevSamples = previousCommittedPcm.size / 4
        val nextSamples = nextCommittedPcm.size / 4

        val boundaryWindow = 588 // Search around 1 sector boundary

        // Duplicate detection:
        // Instead of searching for repeated bytes which causes false positives on silence,
        // we deterministically infer duplicates based on the trim metadata.
        // If actualTrimSamples < expectedTrimSamples, we insufficiently trimmed and committed a duplicate part.
        if (actualTrimSamples < expectedTrimSamples) {
            val duplicate = expectedTrimSamples - actualTrimSamples
            anomalies.add(AlignmentIssue.DuplicateSamples(duplicate, prevSamples - duplicate))
        }

        // Dropped detection:
        // If actualTrimSamples > expectedTrimSamples, we trimmed too much.
        if (actualTrimSamples > expectedTrimSamples) {
             val dropped = actualTrimSamples - expectedTrimSamples
             anomalies.add(AlignmentIssue.DroppedSamples(dropped, prevSamples))
        }

        return AlignmentValidationResult(
            valid = anomalies.isEmpty(),
            anomalies = anomalies
        )
    }

    fun validateFinalTrack(
        finalPcmSizeBytes: Long,
        expectedSamples: Long,
        totalOverlapTrimmedSamples: Long,
        expectedTotalOverlapTrimmedSamples: Long
    ): AlignmentValidationResult {
        val anomalies = mutableListOf<AlignmentIssue>()

        val finalSamples = finalPcmSizeBytes / 4

        if (finalSamples != expectedSamples) {
            val diff = (expectedSamples - finalSamples).toInt()
            anomalies.add(AlignmentIssue.BoundaryDiscontinuity(mismatchSampleOffset = diff))
        }

        if (totalOverlapTrimmedSamples != expectedTotalOverlapTrimmedSamples) {
            anomalies.add(AlignmentIssue.InvalidOverlapTrim(
                expectedTrimSamples = expectedTotalOverlapTrimmedSamples.toInt(),
                actualTrimSamples = totalOverlapTrimmedSamples.toInt()
            ))
        }

        return AlignmentValidationResult(
            valid = anomalies.isEmpty(),
            anomalies = anomalies
        )
    }
}
