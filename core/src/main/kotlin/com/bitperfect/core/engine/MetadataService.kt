package com.bitperfect.core.engine

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Log

@Serializable
data class AlbumMetadata(
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val year: String = "N/A",
    val tracks: List<String> = emptyList()
)

@Serializable
private data class MusicBrainzResponse(
    val releases: List<Release>? = null
)

@Serializable
private data class Release(
    val title: String? = null,
    val date: String? = null,
    @kotlinx.serialization.SerialName("artist-credit")
    val artistCredit: List<ArtistCredit>? = null,
    val media: List<Medium>? = null
)

@Serializable
private data class ArtistCredit(
    val name: String? = null
)

@Serializable
private data class Medium(
    val tracks: List<Track>? = null
)

@Serializable
private data class Track(
    val title: String? = null
)

class MetadataService {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    suspend fun fetchMetadata(discId: String): AlbumMetadata {
        return try {
            val response: MusicBrainzResponse = client.get("https://musicbrainz.org/ws/2/discid/$discId") {
                parameter("fmt", "json")
                parameter("inc", "recordings+artists")
                header("User-Agent", "BitPerfect/1.0 ( https://github.com/example/bitperfect )")
            }.body()

            val release = response.releases?.firstOrNull()
            if (release != null) {
                val artist = release.artistCredit?.firstOrNull()?.name ?: "Unknown Artist"
                val album = release.title ?: "Unknown Album"
                val year = release.date?.take(4) ?: "N/A"
                val tracks = release.media?.flatMap { it.tracks ?: emptyList() }?.map { it.title ?: "Unknown Track" } ?: emptyList()

                AlbumMetadata(artist, album, year, tracks)
            } else {
                AlbumMetadata()
            }
        } catch (e: Exception) {
            Log.e("MetadataService", "Error fetching metadata: ${e.message}")
            AlbumMetadata()
        }
    }
}
