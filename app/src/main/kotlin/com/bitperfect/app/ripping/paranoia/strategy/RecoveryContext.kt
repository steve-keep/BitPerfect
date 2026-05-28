package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.CandidateHistory
import com.bitperfect.app.ripping.paranoia.DriftEvent
import com.bitperfect.app.ripping.paranoia.RipConfidence

data class RecoveryContext(
    val trackNumber: Int,
    val chunkStartLba: Int,
    val chunkEndLba: Int,
    val rereadAttempt: Int,
    val candidateHistory: CandidateHistory?,
    val driftEvent: DriftEvent?,
    val previousConfidence: RipConfidence
)
