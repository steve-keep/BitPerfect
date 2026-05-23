package com.bitperfect.app.library

import kotlin.random.Random

internal class MixEngine {
    fun generate(
        candidates: List<TrackCandidate>,
        blueprints: List<MixBlueprint>,
        weekSeed: Long
    ): List<AiMix> {
        val mixes = mutableListOf<AiMix>()
        val currentTime = System.currentTimeMillis()

        for (blueprint in blueprints) {
            val scoredCandidates = candidates.mapNotNull { candidate ->
                val score = scoreCandidate(candidate, blueprint)
                if (score > 0f) {
                    candidate to score
                } else {
                    null
                }
            }

            if (scoredCandidates.size >= 6) {
                val selectedCandidates = scoredCandidates
                    .sortedWith(compareBy<Pair<TrackCandidate, Float>> { it.first.energy == null }.thenBy { it.first.energy })
                    .distinctBy { it.first.trackId }
                    .take(15)
                    .map { it.first }

                if (selectedCandidates.size >= 6) {
                    val tracks = selectedCandidates.map { candidate ->
                        AiMixTrack(
                            trackId = candidate.trackId,
                            artist = candidate.artist,
                            title = candidate.trackTitle,
                            albumTitle = candidate.albumTitle,
                            albumId = candidate.albumId
                        )
                    }

                    mixes.add(
                        AiMix(
                            generatedAt = currentTime,
                            name = blueprint.name,
                            description = blueprint.description,
                            tracks = tracks
                        )
                    )
                }
            }
        }

        val random = Random(weekSeed)
        return mixes.shuffled(random)
    }

    private fun scoreCandidate(candidate: TrackCandidate, blueprint: MixBlueprint): Float {
        if (blueprint.verifiedOnly && !candidate.accurateRipVerified) {
            return 0f
        }

        if (blueprint.decade != null) {
            val year = candidate.year?.toIntOrNull()
            if (year == null) {
                return 0f
            }
            val decadeStart = blueprint.decade.take(4).toIntOrNull()
            if (decadeStart == null || year < decadeStart || year >= decadeStart + 10) {
                return 0f
            }
        }

        if (blueprint.genres.isNotEmpty()) {
            val candidateGenres = buildList {
                if (candidate.genre != null) add(candidate.genre)
                addAll(candidate.tags)
            }.map { it.lowercase() }

            val blueprintGenresLower = blueprint.genres.map { it.lowercase() }
            val hasGenreMatch = blueprintGenresLower.any { candidateGenres.contains(it) }

            if (!hasGenreMatch) {
                return 0f
            }
        }

        val bpmInRange = if (candidate.bpm != null) {
            candidate.bpm >= blueprint.bpmMin && candidate.bpm <= blueprint.bpmMax
        } else {
            false
        }

        val energyInRange = if (candidate.energy != null) {
            candidate.energy >= blueprint.energyMin && candidate.energy <= blueprint.energyMax
        } else {
            false
        }

        val bothSoft = candidate.bpm == null && candidate.energy == null

        val score = (if (bpmInRange) 0.4f else 0.0f) + (if (energyInRange) 0.4f else 0.0f) + (if (bothSoft) 0.2f else 0.0f)

        return score
    }
}
