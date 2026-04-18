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
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AlbumMetadata(
    val id: String = "",
    val artist: String = "Unknown Artist",
    val album: String = "Unknown Album",
    val year: String = "N/A",
    val tracks: List<String> = emptyList(),
    val albumArtUrl: String? = null,
    val country: String? = null,
    val label: String? = null
)

@Serializable
private data class MusicBrainzResponse(
    val releases: List<Release>? = null
)

@Serializable
private data class Release(
    val id: String = "",
    val title: String? = null,
    val date: String? = null,
    val country: String? = null,
    @kotlinx.serialization.SerialName("label-info")
    val labelInfo: List<LabelInfo>? = null,
    @kotlinx.serialization.SerialName("artist-credit")
    val artistCredit: List<ArtistCredit>? = null,
    val media: List<Medium>? = null
)

@Serializable
private data class LabelInfo(
    val label: Label? = null
)

@Serializable
private data class Label(
    val name: String? = null
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

    private val sessionCache = ConcurrentHashMap<String, List<AlbumMetadata>>()

    suspend fun fetchMetadata(discId: String): List<AlbumMetadata> {
        val cached = sessionCache[discId]
        if (cached != null) {
            return cached
        }

        return try {
            val response: MusicBrainzResponse = client.get("https://musicbrainz.org/ws/2/discid/$discId") {
                parameter("fmt", "json")
                parameter("inc", "recordings+artists+release-groups+labels")
                header("User-Agent", "BitPerfect/1.0 ( https://steve-keep.github.io/BitPerfect/ )")
            }.body()

            val releases = response.releases ?: emptyList()
            val metadataList = releases.map { release ->
                val id = release.id
                val artist = release.artistCredit?.firstOrNull()?.name ?: "Unknown Artist"
                val album = release.title ?: "Unknown Album"
                val year = release.date?.take(4) ?: "N/A"
                val country = release.country
                val label = release.labelInfo?.firstOrNull()?.label?.name
                val tracks = release.media?.flatMap { it.tracks ?: emptyList() }?.map { it.title ?: "Unknown Track" } ?: emptyList()
                val albumArtUrl = if (id.isNotEmpty()) "https://coverartarchive.org/release/$id/front-250" else null

                AlbumMetadata(
                    id = id,
                    artist = artist,
                    album = album,
                    year = year,
                    tracks = tracks,
                    albumArtUrl = albumArtUrl,
                    country = country,
                    label = label
                )
            }

            sessionCache[discId] = metadataList
            metadataList
        } catch (e: Exception) {
            Log.e("MetadataService", "Error fetching metadata: ${e.message}")
            emptyList()
        }
    }
}
