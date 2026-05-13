package com.bitperfect.core.services

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
    val artworkUrl100: String? = null
)

data class ItunesArtwork(
    val previewUrl: String,   // 600x600bb
    val highResUrl: String    // 3000x3000bb
)

private fun normalise(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex(" +"), " ").trim()

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

    suspend fun fetchItunesArtwork(artist: String, album: String): ItunesArtwork? = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedAlbum = URLEncoder.encode(album, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedArtist+$encodedAlbum&media=music&limit=20"

            val response: ItunesSearchResponse = client.get(url).body()

            if (response.results.isEmpty()) return@withContext null

            val normalisedArtist = normalise(artist)
            val normalisedAlbum = normalise(album)

            val match = response.results.firstOrNull {
                it.artworkUrl100 != null &&
                it.artistName?.let { name -> normalise(name).contains(normalisedArtist) } == true &&
                it.collectionName?.let { name -> normalise(name).contains(normalisedAlbum) } == true
            }

            if (match?.artworkUrl100 != null) {
                val base = match.artworkUrl100
                val previewUrl = base.replace("100x100bb", "600x600bb")
                val highResUrl = base.replace("100x100bb", "3000x3000bb")
                return@withContext ItunesArtwork(previewUrl, highResUrl)
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
