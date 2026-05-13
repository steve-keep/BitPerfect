package com.bitperfect.core.services

import android.content.Context
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.core.utils.computeMusicBrainzTocString
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

class MusicBrainzRepository(private val context: Context) {
    companion object {
        const val CACHE_EXPIRY_MS = 60L * 60 * 1000 // 1 hour
    }

    private val TAG = "MusicBrainzRepository"

    private val json = Json { ignoreUnknownKeys = true }

    private var client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
            json(json, contentType = ContentType.Text.Plain)
        }
        defaultRequest {
            header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
        }
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            cleanupExpiredCache()
        }
    }

    internal constructor(context: Context, engine: HttpClientEngine) : this(context) {
        client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
                json(json, contentType = ContentType.Text.Plain)
            }
            defaultRequest {
                header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
            }
        }
    }

    private fun cleanupExpiredCache() {
        try {
            val cacheDir = context.cacheDir
            if (cacheDir.exists() && cacheDir.isDirectory) {
                val files = cacheDir.listFiles { _, name -> name.startsWith("mb_") && name.endsWith(".json") }
                if (files != null) {
                    val now = System.currentTimeMillis()
                    for (file in files) {
                        if (now - file.lastModified() >= CACHE_EXPIRY_MS) {
                            val deleted = file.delete()
                            if (deleted) {
                                AppLogger.d(TAG, "Deleted expired cache file: ${file.name}")
                            } else {
                                AppLogger.e(TAG, "Failed to delete expired cache file: ${file.name}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cleaning up expired cache: ${e.message}")
        }
    }

    suspend fun lookup(toc: DiscToc): DiscMetadata? = withContext(Dispatchers.IO) {
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")

        try {
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_EXPIRY_MS) {
                val jsonString = cacheFile.readText()
                val response: MbDiscIdResponse = json.decodeFromString(jsonString)
                AppLogger.d(TAG, "Loaded metadata from cache for discId: $discId")
                return@withContext mapToMetadata(response, discId)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading from cache for discId $discId: ${e.message}")
        }

        try {
            AppLogger.d(TAG, "Fetching metadata from MusicBrainz for discId: $discId")
            val url = "https://musicbrainz.org/ws/2/discid/$discId?fmt=json&inc=artist-credits+recordings"
            val httpResponse = client.get(url)

            if (httpResponse.status == HttpStatusCode.NotFound) {
                AppLogger.d(TAG, "Disc ID not found, attempting TOC fuzzy lookup")
                return@withContext lookupByToc(toc, discId, cacheFile)
            }

            val responseBody = httpResponse.bodyAsText()
            val response: MbDiscIdResponse = json.decodeFromString(responseBody)

            // On success, save the raw network JSON string to the cache file
            try {
                cacheFile.writeText(responseBody)
                AppLogger.d(TAG, "Saved metadata to cache for discId: $discId")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error writing to cache for discId $discId: ${e.message}")
            }

            return@withContext mapToMetadata(response, discId)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error fetching from MusicBrainz for discId $discId: ${e.message}")
            throw e
        }
    }

    private suspend fun lookupByToc(toc: DiscToc, discId: String, cacheFile: File): DiscMetadata? {
        // Build MB TOC string: firstTrack + trackCount + leadOutLba+150 + (lba+150 for each track)
        val tocStr = computeMusicBrainzTocString(toc)
        val url = "https://musicbrainz.org/ws/2/discid/-?toc=$tocStr&fmt=json&inc=artist-credits+recordings&cdstubs=no"

        AppLogger.d(TAG, "TOC fuzzy lookup: $url")
        val httpResponse = client.get(url)
        if (httpResponse.status != HttpStatusCode.OK) {
            AppLogger.d(TAG, "TOC fuzzy lookup returned ${httpResponse.status}")
            return null
        }

        val responseBody = httpResponse.bodyAsText()
        val response: MbDiscIdResponse = json.decodeFromString(responseBody)
        try { cacheFile.writeText(responseBody) } catch (e: Exception) { /* best effort */ }
        return mapToMetadata(response, discId)
    }

    private fun mapToMetadata(response: MbDiscIdResponse, queryDiscId: String): DiscMetadata? {
        if (response.releases.isEmpty()) return null

        val release = response.releases.maxByOrNull { release ->
            var score = 0
            // Exact disc ID match is the strongest signal
            if (release.media.any { m -> m.discs.any { d -> d.id == queryDiscId } }) score += 100
            // Prefer releases with cover art
            if (release.coverArtArchive?.front == true) score += 10
            // Prefer releases with a date
            if (!release.date.isNullOrBlank()) score += 5
            // Prefer releases with a barcode
            if (!release.barcode.isNullOrBlank()) score += 2
            score
        } ?: return null

        val albumTitle = release.title
        val artistName = release.artistCredit.firstOrNull()?.artist?.name ?: "Unknown Artist"

        // Find the specific media object corresponding to the queried discId
        val targetMedia = release.media.find { media ->
            media.discs.any { disc -> disc.id == queryDiscId }
        } ?: release.media.firstOrNull()

        val trackTitles = targetMedia?.tracks?.map { it.title }?.takeIf { it.isNotEmpty() } ?: emptyList()
        val discNumber = targetMedia?.position
        val totalDiscs = release.media.size.takeIf { it > 0 }
        val mbReleaseId = release.id

        val year = release.date?.takeIf { it.length >= 4 }?.substring(0, 4)

        val albumArtist = release.artistCredit.joinToString("") {
            (it.name ?: it.artist.name) + (it.joinphrase ?: "")
        }.takeIf { it.isNotBlank() }

        val genre = release.genres.firstOrNull()?.name
            ?: release.artistCredit.firstOrNull()?.artist?.genres?.firstOrNull()?.name

        return DiscMetadata(
            albumTitle = albumTitle,
            artistName = artistName,
            trackTitles = trackTitles,
            mbReleaseId = mbReleaseId,
            year = year,
            genre = genre,
            albumArtist = albumArtist,
            discNumber = discNumber,
            totalDiscs = totalDiscs
        )
    }
}
