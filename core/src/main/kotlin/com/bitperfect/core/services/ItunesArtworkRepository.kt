package com.bitperfect.core.services

import com.bitperfect.core.utils.AppLogger
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

@Serializable
data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesAlbumResult> = emptyList()
)



@Serializable
data class ItunesAlbumResult(
    val artistName: String? = null,
    val collectionName: String? = null,
    val collectionId: Long? = null,
    val artworkUrl100: String? = null,
    val wrapperType: String? = null,
    val collectionType: String? = null,
    val kind: String? = null,
    val trackCount: Int? = null,
    val releaseDate: String? = null
)

data class ItunesArtwork(
    val previewUrl: String,   // 600x600bb
    val highResUrl: String    // 3000x3000bb
)

private val editionPatterns = listOf(
    "remixed and remastered",
    "digitally remastered",
    "uber deluxe limited edition",
    "super deluxe edition",
    "super deluxe",
    "deluxe expanded edition",
    "deluxe edition",
    "deluxe version",
    "deluxe",
    "expanded edition",
    "expanded",
    "special edition",
    "special version",
    "limited edition",
    "limited version",
    "collector s edition",
    "collector edition",
    "legacy edition",
    "anniversary edition",
    "anniversary remaster",
    "remastered version",
    "remastered",
    "remaster",
    "reissue",
    "bonus track version",
    "bonus track",
    "bonus edition"
)

private fun simplifyAlbumTitle(value: String): String {
    var normalized = value
        .lowercase()
        .replace("&", "and")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    editionPatterns.forEach {
        normalized = normalized.replace(it, "")
    }

    return normalized
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalise(value: String): String =
    value.lowercase()
        .replace("&", "and")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeArtistName(value: String): String {
    val normalized = normalise(value)
    return if (normalized.startsWith("the ")) {
        normalized.removePrefix("the ").trim()
    } else {
        normalized
    }
}

private fun calculateWordOverlap(expected: String, candidate: String): Float {
    if (expected.isEmpty() || candidate.isEmpty()) return 0f

    val expectedWords = expected.split(" ").filter { it.isNotEmpty() }
    val candidateWords = candidate.split(" ").filter { it.isNotEmpty() }

    if (expectedWords.isEmpty() || candidateWords.isEmpty()) return 0f

    val sharedWords = expectedWords.intersect(candidateWords.toSet()).size
    return sharedWords.toFloat() / maxOf(expectedWords.size, candidateWords.size)
}

private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
    if (lhs == rhs) return 0
    if (lhs.isEmpty()) return rhs.length
    if (rhs.isEmpty()) return lhs.length

    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = IntArray(lhsLength) { it }
    var newCost = IntArray(lhsLength) { 0 }

    for (i in 1 until rhsLength) {
        newCost[0] = i

        for (j in 1 until lhsLength) {
            val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1

            newCost[j] = minOf(costInsert, costDelete, costReplace)
        }
        val swap = cost
        cost = newCost
        newCost = swap
    }

    return cost[lhsLength - 1]
}

class ItunesArtworkRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private var client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
            json(json, contentType = ContentType.Text.Plain)
            json(json, contentType = ContentType.Application.Json)
            json(json, contentType = ContentType.Text.JavaScript)
        }
        defaultRequest {
            header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
        }
    }

    internal constructor(context: Context, engine: HttpClientEngine) : this(context) {
        client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
                json(json, contentType = ContentType.Text.Plain)
                json(json, contentType = ContentType.Application.Json)
                json(json, contentType = ContentType.Text.JavaScript)
            }
            defaultRequest {
                header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
            }
        }
    }

    // Represents the scored evaluation of a candidate
    data class CandidateScore(
        val totalScore: Int,
        val penaltiesApplied: List<String>,
        val exactArtistMatch: Boolean,
        val exactAlbumMatch: Boolean,
        val artistOverlapRatio: Float,
        val albumOverlapRatio: Float,
        val levenshteinCost: Int
    )

    private fun score(
        result: ItunesAlbumResult,
        expectedNormalisedArtist: String,
        expectedSimplifiedAlbum: String,
        expectedTrackCount: Int?
    ): CandidateScore {
        var score = 0
        val penaltiesApplied = mutableListOf<String>()

        val candidateArtist = result.artistName?.let { normalizeArtistName(it) } ?: ""
        val candidateAlbum = result.collectionName?.let { normalise(it) } ?: ""
        val candidateSimplifiedAlbum = result.collectionName?.let { simplifyAlbumTitle(it) } ?: ""

        val exactArtistMatch = candidateArtist == expectedNormalisedArtist && expectedNormalisedArtist.isNotEmpty()
        val exactAlbumMatch = candidateSimplifiedAlbum == expectedSimplifiedAlbum && expectedSimplifiedAlbum.isNotEmpty()

        val artistOverlapRatio = calculateWordOverlap(expectedNormalisedArtist, candidateArtist)
        val albumOverlapRatio = calculateWordOverlap(expectedSimplifiedAlbum, candidateSimplifiedAlbum)

        // 1. & 2. Exact match bonuses
        if (exactArtistMatch && exactAlbumMatch) {
            score += 2000
        } else {
            if (exactArtistMatch) {
                score += 500
            }
            if (exactAlbumMatch) {
                score += 1000
            }
        }

        // 3. Strong album overlap
        if (!exactAlbumMatch && albumOverlapRatio >= 0.75f) {
            score += 300
        }

        // 4. Strong artist overlap
        if (!exactArtistMatch && artistOverlapRatio >= 0.75f) {
            score += 200
        }

        // Penalties for weak/no artist match
        if (!exactArtistMatch) {
            if (artistOverlapRatio < 0.5f && artistOverlapRatio > 0.0f) {
                score -= 300
                penaltiesApplied.add("Weak artist overlap (-300)")
            } else if (artistOverlapRatio == 0.0f) {
                score -= 500
                penaltiesApplied.add("No artist overlap (-500)")
            }
        }

        // 5. Track count similarity
        if (expectedTrackCount != null && result.trackCount == expectedTrackCount) {
            score += 40
        } else if (expectedTrackCount != null && result.trackCount != null) {
            val diff = Math.abs(result.trackCount - expectedTrackCount)
            if (diff <= 2) {
                score += 10
            }
        }

        val contentPenalties = listOf(
            "instrumental", "remix", "tribute", "karaoke", "lofi",
            "greatest hits", "best of", "cover", "live"
        )
        if (contentPenalties.any { candidateAlbum.contains(it) }) {
            score -= 100
            penaltiesApplied.add("Content penalty (-100)")
        }

        if (result.wrapperType == "collection") {
            score += 100
        }

        // 6. Levenshtein tie-breakers
        val levenshteinCost = levenshtein(expectedSimplifiedAlbum, candidateSimplifiedAlbum)
        score -= levenshteinCost

        return CandidateScore(
            totalScore = score,
            penaltiesApplied = penaltiesApplied,
            exactArtistMatch = exactArtistMatch,
            exactAlbumMatch = exactAlbumMatch,
            artistOverlapRatio = artistOverlapRatio,
            albumOverlapRatio = albumOverlapRatio,
            levenshteinCost = levenshteinCost
        )
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 60
        private const val BEST_EFFORT_THRESHOLD = 30
    }

    suspend fun fetchItunesArtwork(artist: String, album: String, expectedTrackCount: Int? = null): ItunesArtwork? = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedAlbum = URLEncoder.encode(album, "UTF-8")

            val queries = listOf(
                Pair("Stage 1 (Primary strict)", "https://itunes.apple.com/search?term=$encodedArtist+$encodedAlbum&media=music&entity=album&attribute=albumTerm&limit=25"),
                Pair("Stage 2 (Relaxed text)", "https://itunes.apple.com/search?term=$encodedArtist+$encodedAlbum&media=music&entity=album&limit=25"),
                Pair("Stage 3 (Album only)", "https://itunes.apple.com/search?term=$encodedAlbum&media=music&entity=album&limit=25"),
                Pair("Stage 4 (Broad media)", "https://itunes.apple.com/search?term=$encodedArtist+$encodedAlbum&media=music&limit=25")
            )

            val expectedNormalisedArtist = normalizeArtistName(artist)
            val expectedSimplifiedAlbum = simplifyAlbumTitle(album)

            var absoluteBestCandidate: ItunesAlbumResult? = null
            var absoluteBestScore = Int.MIN_VALUE
            var absoluteBestReason: String? = null

            for ((stageName, url) in queries) {
                AppLogger.d("ItunesArtworkRepository", "Executing $stageName query: $url")
                val response: ItunesSearchResponse = client.get(url).body()

                if (response.results.isEmpty()) {
                    AppLogger.d("ItunesArtworkRepository", "$stageName returned 0 results")
                    continue
                }

                val validCandidates = response.results.filter { result ->
                    val hasRequiredFields =
                        result.artistName != null &&
                        result.collectionName != null &&
                        result.artworkUrl100 != null

                    val isAlbumCollection =
                        result.wrapperType == "collection" &&
                        result.collectionType == "Album"

                    val isTrackFallback =
                        result.wrapperType == "track" &&
                        result.kind == "song"

                    hasRequiredFields && (isAlbumCollection || isTrackFallback)
                }

                // Deduplicate within the current stage using collectionId, falling back to artistName + collectionName
                val deduplicatedCandidates = validCandidates.distinctBy {
                    it.collectionId?.toString() ?: "${it.artistName}-${it.collectionName}"
                }

                AppLogger.d("ItunesArtworkRepository", "Artwork candidates after dedupe: ${deduplicatedCandidates.size}")

                var stageBestMatch: ItunesAlbumResult? = null
                var stageBestScore = Int.MIN_VALUE
                var stageBestReason: String? = null

                for (candidate in deduplicatedCandidates) {
                    val candidateScore = score(candidate, expectedNormalisedArtist, expectedSimplifiedAlbum, expectedTrackCount)

                    val candidateArtist = candidate.artistName?.let { normalizeArtistName(it) } ?: ""
                    val candidateSimplifiedAlbum = candidate.collectionName?.let { simplifyAlbumTitle(it) } ?: ""

                    AppLogger.d("ItunesArtworkRepository", "[$stageName] Evaluating Candidate:")
                    AppLogger.d("ItunesArtworkRepository", "  - Candidate Type: wrapper=${candidate.wrapperType} kind=${candidate.kind}")
                    AppLogger.d("ItunesArtworkRepository", "  - Expected Artist: '$expectedNormalisedArtist' | Candidate: '$candidateArtist'")
                    AppLogger.d("ItunesArtworkRepository", "  - Exact Artist Match: ${candidateScore.exactArtistMatch} | Overlap Ratio: ${candidateScore.artistOverlapRatio}")
                    AppLogger.d("ItunesArtworkRepository", "  - Expected Album: '$expectedSimplifiedAlbum' | Candidate: '$candidateSimplifiedAlbum'")
                    AppLogger.d("ItunesArtworkRepository", "  - Exact Album Match: ${candidateScore.exactAlbumMatch} | Overlap Ratio: ${candidateScore.albumOverlapRatio}")
                    AppLogger.d("ItunesArtworkRepository", "  - Levenshtein Distance: ${candidateScore.levenshteinCost}")
                    AppLogger.d("ItunesArtworkRepository", "  - Penalties: ${candidateScore.penaltiesApplied}")
                    AppLogger.d("ItunesArtworkRepository", "  - Final Score: ${candidateScore.totalScore}")

                    // Sanity validation check
                    val passesSanityCheck = (candidateScore.exactArtistMatch || candidateScore.artistOverlapRatio >= 0.9f) &&
                                            (candidateScore.exactAlbumMatch || candidateScore.albumOverlapRatio >= 0.75f)

                    if (!passesSanityCheck) {
                        AppLogger.d("ItunesArtworkRepository", "  - REJECTED: Failed sanity validation (needs strong artist AND album match)")
                        continue
                    }

                    if (candidateScore.totalScore > stageBestScore) {
                        val reason = "Beat previous best score ($stageBestScore) with new score (${candidateScore.totalScore})"
                        AppLogger.d("ItunesArtworkRepository", "  - ACCEPTED as current stage best: $reason")
                        stageBestScore = candidateScore.totalScore
                        stageBestMatch = candidate
                        stageBestReason = reason
                    } else {
                        AppLogger.d("ItunesArtworkRepository", "  - ACCEPTED but did not beat stage best score ($stageBestScore)")
                    }
                }

                if (stageBestMatch != null) {
                    if (stageBestScore >= CONFIDENCE_THRESHOLD) {
                        AppLogger.d("ItunesArtworkRepository", "Selected best candidate in $stageName: ${stageBestMatch.collectionName} with score: $stageBestScore (>= threshold $CONFIDENCE_THRESHOLD)")
                        AppLogger.d("ItunesArtworkRepository", "Winning reason: $stageBestReason")
                        return@withContext buildArtwork(stageBestMatch)
                    } else {
                        AppLogger.d("ItunesArtworkRepository", "Best candidate in $stageName narrowly missed threshold: ${stageBestMatch.collectionName} with score: $stageBestScore (< threshold $CONFIDENCE_THRESHOLD). Trying relaxed search...")
                    }

                    if (stageBestScore > absoluteBestScore) {
                        absoluteBestScore = stageBestScore
                        absoluteBestCandidate = stageBestMatch
                        absoluteBestReason = stageBestReason
                    }
                } else {
                    AppLogger.d("ItunesArtworkRepository", "No valid candidate found after filtering and scoring in $stageName")
                }
            }

            if (absoluteBestCandidate != null && absoluteBestScore >= BEST_EFFORT_THRESHOLD) {
                AppLogger.d("ItunesArtworkRepository", "Using best-effort fallback candidate: ${absoluteBestCandidate.collectionName} with score: $absoluteBestScore")
                AppLogger.d("ItunesArtworkRepository", "Winning reason: $absoluteBestReason")
                return@withContext buildArtwork(absoluteBestCandidate)
            }

            AppLogger.d("ItunesArtworkRepository", "No acceptable artwork match found across all stages")
            null
        } catch (e: Exception) {
            AppLogger.e("ItunesArtworkRepository", "Error fetching artwork", e)
            null
        }
    }

    private fun buildArtwork(candidate: ItunesAlbumResult): ItunesArtwork? {
        val base = candidate.artworkUrl100 ?: return null
        val previewUrl = base.replace("100x100bb", "600x600bb")
        val highResUrl = base.replace("100x100bb", "3000x3000bb")
        return ItunesArtwork(previewUrl, highResUrl)
    }
}
