package com.bitperfect.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixEngineTest {

    private val engine = MixEngine()

    private val baseBlueprint = MixBlueprint(
        id = "bp_1",
        name = "Test Mix",
        description = "Test Description",
        moods = emptyList(),
        genres = emptyList(),
        bpmMin = 0,
        bpmMax = 200,
        energyMin = 0.0f,
        energyMax = 1.0f,
        verifiedOnly = false,
        decade = null
    )

    private fun createCandidate(id: Long, verified: Boolean = true, genre: String? = null, energy: Float? = 0.5f, year: String? = null): TrackCandidate {
        return TrackCandidate(
            trackId = id,
            artist = "Artist $id",
            albumTitle = "Album $id",
            albumId = id,
            trackTitle = "Title $id",
            bpm = 100f,
            initialKey = null,
            energy = energy,
            accurateRipVerified = verified,
            genre = genre,
            year = year,
            tags = emptyList()
        )
    }

    @Test
    fun emptyInput_producesNoMixes() {
        val result = engine.generate(emptyList(), listOf(baseBlueprint), 1L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun verifiedOnlyGate_excludesUnverified() {
        val bp = baseBlueprint.copy(verifiedOnly = true)
        val candidates = (1L..6L).map { createCandidate(it, verified = false) }
        val result = engine.generate(candidates, listOf(bp), 1L)
        assertTrue(result.isEmpty())

        val candidatesVerified = (1L..6L).map { createCandidate(it, verified = true) }
        val resultVerified = engine.generate(candidatesVerified, listOf(bp), 1L)
        assertEquals(1, resultVerified.size)
    }

    @Test
    fun genreFilter_matchesCaseInsensitive() {
        val bp = baseBlueprint.copy(genres = listOf("Jazz"))
        val candidatesMatch = (1L..6L).map { createCandidate(it, genre = "jazz") }
        val resultMatch = engine.generate(candidatesMatch, listOf(bp), 1L)
        assertEquals(1, resultMatch.size)

        val candidatesNoMatch = (1L..6L).map { createCandidate(it, genre = "rock") }
        val resultNoMatch = engine.generate(candidatesNoMatch, listOf(bp), 1L)
        assertTrue(resultNoMatch.isEmpty())
    }

    @Test
    fun minimumThreshold_sixRequired() {
        val bp = baseBlueprint
        val candidatesFive = (1L..5L).map { createCandidate(it) }
        val resultFive = engine.generate(candidatesFive, listOf(bp), 1L)
        assertTrue(resultFive.isEmpty())

        val candidatesSix = (1L..6L).map { createCandidate(it) }
        val resultSix = engine.generate(candidatesSix, listOf(bp), 1L)
        assertEquals(1, resultSix.size)
        assertEquals(6, resultSix[0].tracks.size)
    }

    @Test
    fun decadeGate_filtersCorrectly() {
        val bp = baseBlueprint.copy(decade = "1980s")
        val candidates = listOf(
            createCandidate(1L, year = "1975"),
            createCandidate(2L, year = "1982"),
            createCandidate(3L, year = "1989"),
            createCandidate(4L, year = "1991"),
            createCandidate(5L, year = "1985"),
            createCandidate(6L, year = "1980"),
            createCandidate(7L, year = "1988"),
            createCandidate(8L, year = "1984")
        )
        val result = engine.generate(candidates, listOf(bp), 1L)
        assertEquals(1, result.size)
        assertEquals(6, result[0].tracks.size)
        val trackIds = result[0].tracks.map { it.trackId }
        assertTrue(trackIds.containsAll(listOf(2L, 3L, 5L, 6L, 7L, 8L)))
    }

    @Test
    fun weeklySeed_producesConsistentOrder() {
        val blueprints = (1..5).map { baseBlueprint.copy(id = "bp_$it", name = "Mix $it") }
        val candidates = (1L..10L).map { createCandidate(it) }

        val result1 = engine.generate(candidates, blueprints, 42L)
        val result2 = engine.generate(candidates, blueprints, 42L)

        assertEquals(result1.map { it.name }, result2.map { it.name })

        val result3 = engine.generate(candidates, blueprints, 99L)
        assertTrue(result1.map { it.name } != result3.map { it.name })
    }
}
