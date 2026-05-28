package com.bitperfect.app.ripping.paranoia

data class AlignmentValidationResult(
    val valid: Boolean,
    val anomalies: List<AlignmentIssue>
)

sealed class AlignmentIssue {
    data class DuplicateSamples(
        val sampleCount: Int,
        val boundaryOffset: Int
    ) : AlignmentIssue()

    data class DroppedSamples(
        val sampleCount: Int,
        val boundaryOffset: Int
    ) : AlignmentIssue()

    data class BoundaryDiscontinuity(
        val mismatchSampleOffset: Int
    ) : AlignmentIssue()

    data class InvalidOverlapTrim(
        val expectedTrimSamples: Int,
        val actualTrimSamples: Int
    ) : AlignmentIssue()
}
