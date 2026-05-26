package com.bitperfect.app.ripping.paranoia

enum class InstabilityType {
    NONE,
    TRANSIENT_MISMATCH,
    STABLE_CONVERGENCE,
    OSCILLATING_MISMATCH,
    PERSISTENT_INSTABILITY
}

data class ReadCandidate(
    val crc32: Long,
    val occurrenceCount: Int,
    val firstSeenAttempt: Int
)

data class CandidateHistory(
    val candidates: List<ReadCandidate>,
    val stableCandidate: ReadCandidate?,
    val instabilityType: InstabilityType,
    val totalAttempts: Int,
    val uniqueCandidates: Int
)
