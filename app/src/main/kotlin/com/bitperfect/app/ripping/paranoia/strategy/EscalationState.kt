package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.CandidateHistory
import com.bitperfect.app.ripping.paranoia.DriftEvent
import com.bitperfect.app.ripping.paranoia.AlignmentValidationResult

data class EscalationState(
    val escalationDepth: Int,
    val previousStrategies: List<String>,
    val candidateHistory: CandidateHistory?,
    val driftEvent: DriftEvent?,
    val validationResult: AlignmentValidationResult?
)
