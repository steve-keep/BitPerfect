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
    val trackCount: Int? = null,
    val releaseDate: String? = null
)

data class ItunesArtwork(
    val previewUrl: String,   // 600x600bb
    val highResUrl: String    // 3000x3000bb
)

private val editionPatterns = listOf(
    "super deluxe edition",
    "super deluxe",
    "deluxe edition",
    "deluxe",
    "expanded edition",
    "expanded",
    "anniversary edition",
    "remastered",
    "remaster",
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

    private fun score(
        result: ItunesAlbumResult,
        normalisedArtist: String,
        expectedSimplifiedAlbum: String,
        expectedNormalisedAlbum: String,
        expectedTrackCount: Int?
    ): Int {
        var score = 0
        val artist = result.artistName?.let { normalise(it) } ?: ""
        val album = result.collectionName?.let { normalise(it) } ?: ""
        val actualSimplifiedAlbum = result.collectionName?.let { simplifyAlbumTitle(it) } ?: ""

        if (artist == normalisedArtist && normalisedArtist.isNotEmpty()) {
            score += 20
        }

        if (actualSimplifiedAlbum == expectedSimplifiedAlbum && expectedSimplifiedAlbum.isNotEmpty()) {
            score += 100
        } else if (expectedSimplifiedAlbum.isNotEmpty() && actualSimplifiedAlbum.isNotEmpty() && (actualSimplifiedAlbum.contains(expectedSimplifiedAlbum) || expectedSimplifiedAlbum.contains(actualSimplifiedAlbum))) {
            score += 60
        }

        if (album == expectedNormalisedAlbum && expectedNormalisedAlbum.isNotEmpty()) {
            score += 20
        }

        if (expectedTrackCount != null && result.trackCount == expectedTrackCount) {
            score += 40
        } else if (expectedTrackCount != null && result.trackCount != null) {
            val diff = Math.abs(result.trackCount - expectedTrackCount)
            if (diff <= 2) {
                score += 10
            }
        }

        val penalties = listOf(
            "instrumental", "remix", "tribute", "karaoke", "lofi",
            "greatest hits", "best of", "cover", "live"
        )
        if (penalties.any { album.contains(it) }) {
            score -= 60
        }

        val levenshteinCost = levenshtein(expectedSimplifiedAlbum, actualSimplifiedAlbum)
        score -= levenshteinCost

        return score
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

            val normalisedArtist = normalise(artist)
            val expectedSimplifiedAlbum = simplifyAlbumTitle(album)
            val expectedNormalisedAlbum = normalise(album)

            var absoluteBestCandidate: ItunesAlbumResult? = null
            var absoluteBestScore = Int.MIN_VALUE

            for ((stageName, url) in queries) {
                AppLogger.d("ItunesArtworkRepository", "Executing $stageName query: $url")
                val response: ItunesSearchResponse = client.get(url).body()

                if (response.results.isEmpty()) {
                    AppLogger.d("ItunesArtworkRepository", "$stageName returned 0 results")
                    continue
                }

                val validCandidates = response.results.filter {
                    it.artworkUrl100 != null && it.wrapperType == "collection" && it.collectionType == "Album"
                }

                // Deduplicate within the current stage using collectionId, falling back to artistName + collectionName
                val deduplicatedCandidates = validCandidates.distinctBy {
                    it.collectionId?.toString() ?: "${it.artistName}-${it.collectionName}"
                }

                AppLogger.d("ItunesArtworkRepository", "Artwork candidates after dedupe: ${deduplicatedCandidates.size}")

                var stageBestMatch: ItunesAlbumResult? = null
                var stageBestScore = Int.MIN_VALUE

                for (candidate in deduplicatedCandidates) {
                    val score = score(candidate, normalisedArtist, expectedSimplifiedAlbum, expectedNormalisedAlbum, expectedTrackCount)

                    val actualSimplified = candidate.collectionName?.let { simplifyAlbumTitle(it) } ?: ""
                    AppLogger.d("ItunesArtworkRepository", "[$stageName] Candidate: '${candidate.collectionName}' (Simplified: '$actualSimplified') | TrackCount: ${candidate.trackCount} | Score: $score")

                    if (score > stageBestScore) {
                        stageBestScore = score
                        stageBestMatch = candidate
                    }
                }

                if (stageBestMatch != null) {
                    if (stageBestScore >= CONFIDENCE_THRESHOLD) {
                        AppLogger.d("ItunesArtworkRepository", "Selected best candidate in $stageName: ${stageBestMatch.collectionName} with score: $stageBestScore (>= threshold $CONFIDENCE_THRESHOLD)")
                        return@withContext buildArtwork(stageBestMatch)
                    } else {
                        AppLogger.d("ItunesArtworkRepository", "Best candidate in $stageName narrowly missed threshold: ${stageBestMatch.collectionName} with score: $stageBestScore (< threshold $CONFIDENCE_THRESHOLD). Trying relaxed search...")
                    }

                    if (stageBestScore > absoluteBestScore) {
                        absoluteBestScore = stageBestScore
                        absoluteBestCandidate = stageBestMatch
                    }
                } else {
                    AppLogger.d("ItunesArtworkRepository", "No valid candidate found after filtering in $stageName")
                }
            }

            if (absoluteBestCandidate != null && absoluteBestScore >= BEST_EFFORT_THRESHOLD) {
                AppLogger.d("ItunesArtworkRepository", "Using best-effort fallback candidate: ${absoluteBestCandidate.collectionName} with score: $absoluteBestScore")
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
