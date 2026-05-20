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

    private fun score(result: ItunesAlbumResult, normalisedArtist: String, normalisedAlbum: String, expectedTrackCount: Int?): Int {
        var score = 0
        val artist = result.artistName?.let { normalise(it) } ?: ""
        val album = result.collectionName?.let { normalise(it) } ?: ""

        if (artist == normalisedArtist) {
            score += 20
        }

        if (album == normalisedAlbum) {
            score += 100
        }

        if (expectedTrackCount != null && result.trackCount == expectedTrackCount) {
            score += 40
        } else if (expectedTrackCount != null && result.trackCount != null) {
            val diff = Math.abs(result.trackCount - expectedTrackCount)
            if (diff <= 2) {
                score += 10
            }
        }

        if (album.contains("deluxe") || album.contains("anniversary") || album.contains("expanded") || album.contains("remaster")) {
            score -= 20
        }

        if (album.contains("instrumental") || album.contains("remix") || album.contains("tribute") || album.contains("karaoke") || album.contains("lofi") || album.contains("greatest hits")) {
            score -= 60
        }

        score -= levenshtein(normalisedAlbum, album)

        return score
    }

    suspend fun fetchItunesArtwork(artist: String, album: String, expectedTrackCount: Int? = null): ItunesArtwork? = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedAlbum = URLEncoder.encode(album, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedArtist+$encodedAlbum&media=music&entity=album&attribute=albumTerm&limit=25"

            AppLogger.d("ItunesArtworkRepository", "Querying: $url")

            val response: ItunesSearchResponse = client.get(url).body()

            if (response.results.isEmpty()) return@withContext null

            val normalisedArtist = normalise(artist)
            val normalisedAlbum = normalise(album)

            val validCandidates = response.results.filter {
                it.artworkUrl100 != null && it.wrapperType == "collection" && it.collectionType == "Album"
            }

            var bestMatch: ItunesAlbumResult? = null
            var bestScore = Int.MIN_VALUE

            for (candidate in validCandidates) {
                val score = score(candidate, normalisedArtist, normalisedAlbum, expectedTrackCount)
                AppLogger.d("ItunesArtworkRepository", "Artwork candidate: ${candidate.collectionName}, score=$score, trackCount=${candidate.trackCount}")
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = candidate
                }
            }

            if (bestMatch != null) {
                AppLogger.d("ItunesArtworkRepository", "Selected best candidate: ${bestMatch.collectionName} with score: $bestScore")
            } else {
                AppLogger.d("ItunesArtworkRepository", "No valid candidate found after filtering")
            }

            if (bestMatch?.artworkUrl100 != null) {
                val base = bestMatch.artworkUrl100
                val previewUrl = base.replace("100x100bb", "600x600bb")
                val highResUrl = base.replace("100x100bb", "3000x3000bb")
                return@withContext ItunesArtwork(previewUrl, highResUrl)
            }
            null
        } catch (e: Exception) {
            AppLogger.e("ItunesArtworkRepository", "Error fetching artwork", e)
            null
        }
    }
}
