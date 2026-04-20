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
    val label: String? = null,
    val source: String = "MusicBrainz"
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

    suspend fun fetchMetadata(discId: String, toc: DiscToc, onLog: ((String) -> Unit)? = null): List<AlbumMetadata> {
        val cached = sessionCache[discId]
        if (cached != null) {
            onLog?.invoke("Metadata found in cache for Disc ID: $discId")
            return cached
        }

        onLog?.invoke("Looking up metadata from MusicBrainz for Disc ID: $discId")
        val mbResults = fetchMusicBrainzMetadata(discId)
        if (mbResults.isNotEmpty()) {
            onLog?.invoke("Found ${mbResults.size} result(s) from MusicBrainz")
            sessionCache[discId] = mbResults
            return mbResults
        }

        onLog?.invoke("Looking up metadata from GnuDB fallback")
        val gnudbResults = fetchGnuDbMetadata(toc)
        if (gnudbResults.isNotEmpty()) {
            onLog?.invoke("Found ${gnudbResults.size} result(s) from GnuDB")
            sessionCache[discId] = gnudbResults
            return gnudbResults
        }

        return emptyList()
    }

    private suspend fun fetchMusicBrainzMetadata(discId: String): List<AlbumMetadata> {
        return try {
            val response: MusicBrainzResponse = client.get("https://musicbrainz.org/ws/2/discid/$discId") {
                parameter("fmt", "json")
                parameter("inc", "recordings+artists+release-groups+labels")
                header("User-Agent", "BitPerfect/1.0 ( https://steve-keep.github.io/BitPerfect/ )")
            }.body()

            val releases = response.releases ?: emptyList()
            releases.map { release ->
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
                    label = label,
                    source = "MusicBrainz"
                )
            }
        } catch (e: Exception) {
            Log.e("MetadataService", "Error fetching from MusicBrainz: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchGnuDbMetadata(toc: DiscToc): List<AlbumMetadata> {
        return try {
            val freedbId = String.format("%08x", toc.computeFreedbId())
            val numTracks = toc.tracks.size
            val trackOffsets = toc.tracks.map { it.startLba + 150 }.joinToString("+")
            val totalSeconds = (toc.leadOutLba - (toc.tracks.firstOrNull()?.startLba ?: 0)) / 75

            val queryCmd = "cddb+query+$freedbId+$numTracks+$trackOffsets+$totalSeconds"
            val queryUrl = "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=$queryCmd&hello=anon+localhost+BitPerfect+1.0&proto=6"

            val queryResponse: String = client.get(queryUrl).body()

            val lines = queryResponse.lines()
            if (lines.isEmpty() || lines[0].startsWith("202")) { // No match
                return emptyList()
            }

            val categoriesAndIds = mutableListOf<Pair<String, String>>()
            if (lines[0].startsWith("200")) { // Exact match
                val parts = lines[0].split(" ")
                if (parts.size >= 3) categoriesAndIds.add(Pair(parts[1], parts[2]))
            } else if (lines[0].startsWith("211") || lines[0].startsWith("210")) { // Inexact matches or exact matches (list follows)
                for (i in 1 until lines.size) {
                    if (lines[i].trim() == ".") break
                    val parts = lines[i].split(" ")
                    if (parts.size >= 2) {
                        categoriesAndIds.add(Pair(parts[0], parts[1]))
                    }
                }
            }

            val metadataList = mutableListOf<AlbumMetadata>()

            for ((category, id) in categoriesAndIds) {
                val readCmd = "cddb+read+$category+$id"
                val readUrl = "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=$readCmd&hello=anon+localhost+BitPerfect+1.0&proto=6"
                val readResponse: String = client.get(readUrl).body()

                val readLines = readResponse.lines()
                var artist = "Unknown Artist"
                var album = "Unknown Album"
                var year = "N/A"
                val tracksMap = mutableMapOf<Int, String>()

                var dtitleRaw = ""

                for (line in readLines) {
                    if (line.startsWith("DTITLE=")) {
                        dtitleRaw += line.substringAfter("DTITLE=").trim()
                    } else if (line.startsWith("DYEAR=")) {
                        year = line.substringAfter("DYEAR=").trim()
                    } else if (line.startsWith("TTITLE")) {
                        val indexStr = line.substringAfter("TTITLE").substringBefore("=")
                        val title = line.substringAfter("=").trim()
                        indexStr.toIntOrNull()?.let {
                            tracksMap[it] = (tracksMap[it] ?: "") + title
                        }
                    }
                }

                if (dtitleRaw.isNotEmpty()) {
                    val parts = dtitleRaw.split(" / ", limit = 2)
                    if (parts.size == 2) {
                        artist = parts[0]
                        album = parts[1]
                    } else {
                        album = dtitleRaw
                    }
                }

                val tracks = tracksMap.entries.sortedBy { it.key }.map { it.value }

                if (tracks.isNotEmpty() || album != "Unknown Album") {
                    metadataList.add(AlbumMetadata(
                        id = id,
                        artist = artist,
                        album = album,
                        year = year,
                        tracks = tracks,
                        source = "GnuDB"
                    ))
                }
            }

            metadataList
        } catch (e: Exception) {
            Log.e("MetadataService", "Error fetching from GnuDB: ${e.message}")
            emptyList()
        }
    }
}
