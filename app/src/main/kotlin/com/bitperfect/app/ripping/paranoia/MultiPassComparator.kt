package com.bitperfect.app.ripping.paranoia

import java.util.zip.CRC32

class MultiPassComparator {

    fun analyze(candidates: List<ByteArray>): CandidateHistory {
        if (candidates.isEmpty()) {
            return CandidateHistory(
                candidates = emptyList(),
                stableCandidate = null,
                instabilityType = InstabilityType.NONE,
                totalAttempts = 0,
                uniqueCandidates = 0
            )
        }

        val sequence = candidates.map { pcm ->
            val crc = CRC32()
            crc.update(pcm)
            crc.value
        }

        val candidateMap = mutableMapOf<Long, ReadCandidate>()
        sequence.forEachIndexed { index, crc ->
            val attempt = index + 1
            val existing = candidateMap[crc]
            if (existing != null) {
                candidateMap[crc] = existing.copy(occurrenceCount = existing.occurrenceCount + 1)
            } else {
                candidateMap[crc] = ReadCandidate(crc, 1, attempt)
            }
        }

        val uniqueCandidates = candidateMap.size

        // A stable candidate is the FIRST candidate to reach count >= 2
        // OR the one that finishes a consecutive streak?
        // User definition: "stable candidate exists if: occurrenceCount >= 2".
        // If multiple have >= 2, we pick the one that appears first with 2? Let's just pick the first one with >= 2.
        val stableCandidate = candidateMap.values.firstOrNull { it.occurrenceCount >= 2 }

        val instabilityType = classifyInstability(sequence, candidateMap.values.toList(), stableCandidate != null)

        return CandidateHistory(
            candidates = candidateMap.values.sortedBy { it.firstSeenAttempt },
            stableCandidate = stableCandidate,
            instabilityType = instabilityType,
            totalAttempts = sequence.size,
            uniqueCandidates = uniqueCandidates
        )
    }

    private fun classifyInstability(
        sequence: List<Long>,
        distinctCandidates: List<ReadCandidate>,
        hasStableCandidate: Boolean
    ): InstabilityType {
        val uniqueCandidates = distinctCandidates.size

        if (uniqueCandidates <= 1) {
            return InstabilityType.NONE
        }

        val oscillationCount = countOscillations(sequence)

        if (hasStableCandidate) {
            // TRANSIENT_MISMATCH:
            // "A, B, B" -> unique=2, no oscillation
            // "A, A, B, B" -> unique=2, no oscillation
            if (uniqueCandidates == 2 && oscillationCount == 0) {
                return InstabilityType.TRANSIENT_MISMATCH
            }

            // STABLE_CONVERGENCE vs OSCILLATING_MISMATCH
            // "A, B, A" -> oscillationCount=1 -> STABLE_CONVERGENCE
            // "A, B, C, A, A" -> oscillationCount=1 (C->A) -> STABLE_CONVERGENCE
            // "A, B, A, B" -> oscillationCount=2 -> OSCILLATING_MISMATCH
            // "A, B, A, B, A" -> oscillationCount=3 -> OSCILLATING_MISMATCH

            if (oscillationCount >= 2) {
                return InstabilityType.OSCILLATING_MISMATCH
            }

            return InstabilityType.STABLE_CONVERGENCE
        }

        if (oscillationCount > 0) {
            return InstabilityType.OSCILLATING_MISMATCH
        }

        return InstabilityType.PERSISTENT_INSTABILITY
    }

    private fun countOscillations(sequence: List<Long>): Int {
        val seen = mutableSetOf<Long>()
        var lastSeen: Long? = null
        var oscillations = 0

        for (item in sequence) {
            if (lastSeen != null && item != lastSeen) {
                if (seen.contains(item)) {
                    oscillations++
                }
            }
            seen.add(item)
            lastSeen = item
        }
        return oscillations
    }
}
