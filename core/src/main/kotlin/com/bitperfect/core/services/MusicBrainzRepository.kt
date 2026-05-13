package com.bitperfect.core.services

import android.content.Context
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import com.bitperfect.core.utils.decodeUnicodeEscapes
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

class MusicBrainzRepository(private val context: Context) {
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

    suspend fun lookup(toc: DiscToc): DiscMetadata? = withContext(Dispatchers.IO) {
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")

        try {
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 30L * 86400 * 1000) {
                val jsonString = cacheFile.readText()
                val response: MbDiscIdResponse = json.decodeFromString(jsonString)
                AppLogger.d(TAG, "Loaded metadata from cache for discId: $discId")
                return@withContext mapToMetadata(response)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading from cache for discId $discId: ${e.message}")
        }

        try {
            AppLogger.d(TAG, "Fetching metadata from MusicBrainz for discId: $discId")
            val url = "https://musicbrainz.org/ws/2/discid/$discId?fmt=json&inc=recordings+artists+genres+artist-credits"
            val httpResponse = client.get(url)

            if (httpResponse.status == HttpStatusCode.NotFound) {
                AppLogger.d(TAG, "MusicBrainz returned 404 Not Found for discId: $discId")
                return@withContext null
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

            return@withContext mapToMetadata(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error fetching from MusicBrainz for discId $discId: ${e.message}")
            throw e
        }
    }

    private fun mapToMetadata(response: MbDiscIdResponse): DiscMetadata? {
        val release = response.releases.firstOrNull() ?: return null
        val albumTitle = release.title.decodeUnicodeEscapes()
        val artistName = (release.artistCredit.firstOrNull()?.artist?.name ?: "Unknown Artist").decodeUnicodeEscapes()
        val trackTitles = release.media.firstOrNull()?.tracks?.map { it.title.decodeUnicodeEscapes() } ?: emptyList()
        val mbReleaseId = release.id

        val year = release.date?.takeIf { it.length >= 4 }?.substring(0, 4)

        val albumArtist = release.artistCredit.joinToString("") {
            (it.name ?: it.artist.name) + (it.joinphrase ?: "")
        }.takeIf { it.isNotBlank() }?.decodeUnicodeEscapes()

        val genre = (release.genres.firstOrNull()?.name
            ?: release.artistCredit.firstOrNull()?.artist?.genres?.firstOrNull()?.name)?.decodeUnicodeEscapes()

        return DiscMetadata(
            albumTitle = albumTitle,
            artistName = artistName,
            trackTitles = trackTitles,
            mbReleaseId = mbReleaseId,
            year = year,
            genre = genre,
            albumArtist = albumArtist
        )
    }
}
