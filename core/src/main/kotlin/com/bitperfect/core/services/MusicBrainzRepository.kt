package com.bitperfect.core.services

import android.content.Context
import android.util.Log
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

class MusicBrainzRepository internal constructor(
    private val context: Context,
    engine: HttpClientEngine
) {
    constructor(context: Context) : this(context, OkHttp.create())

    private val TAG = "MusicBrainzRepository"
    private val BASE_URL = "https://musicbrainz.org/ws/2"
    private val CACHE_TTL_MS = 30L * 86400 * 1000 // 30 days

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(json)
            json(json, contentType = ContentType.Text.Plain)
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
        }
    }

    suspend fun lookup(toc: DiscToc): DiscMetadata? = withContext(Dispatchers.IO) {
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")

        // 1. Check cache
        try {
            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS)) {
                val jsonString = cacheFile.readText()
                val response: MbDiscIdResponse = json.decodeFromString(jsonString)
                val meta = mapResponseToMetadata(response)
                if (meta != null) {
                    AppLogger.d(TAG, "Loaded metadata from cache for discId $discId")
                    return@withContext meta
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading from cache: ${Log.getStackTraceString(e)}")
        }

        // 2. Fetch from network
        try {
            AppLogger.d(TAG, "Fetching metadata from MusicBrainz for discId $discId...")
            val response: HttpResponse = client.get("$BASE_URL/discid/$discId?fmt=json&inc=recordings+artists")

            if (response.status == HttpStatusCode.NotFound) {
                AppLogger.d(TAG, "Disc ID $discId not found on MusicBrainz")
                return@withContext null
            }

            if (!response.status.isSuccess()) {
                AppLogger.e(TAG, "Failed to fetch metadata. Status: ${response.status}")
                return@withContext null
            }

            val rawJsonString: String = response.bodyAsText()
            val parsedResponse: MbDiscIdResponse = json.decodeFromString(rawJsonString)

            val meta = mapResponseToMetadata(parsedResponse)
            if (meta != null) {
                // Save to cache
                cacheFile.writeText(rawJsonString)
                AppLogger.d(TAG, "Successfully fetched and cached metadata for discId $discId")
                return@withContext meta
            }

            return@withContext null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error fetching from network: ${Log.getStackTraceString(e)}")
            return@withContext null
        }
    }

    private fun mapResponseToMetadata(response: MbDiscIdResponse): DiscMetadata? {
        val release = response.releases.firstOrNull() ?: return null

        val albumTitle = release.title
        val artistName = release.artistCredit.firstOrNull()?.artist?.name ?: "Unknown Artist"
        val trackTitles = release.media.firstOrNull()?.tracks?.map { it.title } ?: emptyList()

        return DiscMetadata(
            albumTitle = albumTitle,
            artistName = artistName,
            trackTitles = trackTitles
        )
    }
}
