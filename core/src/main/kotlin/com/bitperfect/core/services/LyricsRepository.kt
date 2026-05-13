package com.bitperfect.core.services

import android.content.Context
import com.bitperfect.core.models.LyricsResult
import com.bitperfect.core.utils.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File

class LyricsRepository(private val context: Context) {
    private val TAG = "LyricsRepository"

    private val json = Json { ignoreUnknownKeys = true }

    private var client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
            json(json, contentType = ContentType.Text.Plain)
        }
        defaultRequest {
            header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
        }
        expectSuccess = false
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
            expectSuccess = false
        }
    }

    suspend fun fetch(
        artistName: String,
        albumTitle: String,
        trackTitle: String,
        trackNumber: Int,
        mbReleaseId: String,
        durationSeconds: Double
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (mbReleaseId.isBlank()) {
            return@withContext null
        }

        val cacheFile = File(context.cacheDir, "lyrics_${mbReleaseId}_${trackNumber}.json")

        try {
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 30L * 86400 * 1000) {
                val jsonString = cacheFile.readText()
                val response: LrclibResponse = json.decodeFromString(jsonString)
                return@withContext mapToResult(response)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading from cache for track $trackNumber: ${e.message}")
        }

        try {
            val url = "https://lrclib.net/api/get"
            val httpResponse = client.get(url) {
                parameter("artist_name", artistName)
                parameter("track_name", trackTitle)
                parameter("album_name", albumTitle)
                parameter("duration", durationSeconds.toLong())
            }

            if (httpResponse.status == HttpStatusCode.NotFound) {
                return@withContext null
            } else if (httpResponse.status != HttpStatusCode.OK) {
                AppLogger.w(TAG, "Lrclib API returned non-200 status: ${httpResponse.status}")
                return@withContext null
            }

            val responseBody = httpResponse.bodyAsText()
            val response: LrclibResponse = json.decodeFromString(responseBody)

            try {
                cacheFile.writeText(responseBody)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error writing to cache for track $trackNumber: ${e.message}")
            }

            return@withContext mapToResult(response)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error fetching lyrics for track $trackNumber: ${e.message}")
            return@withContext null
        }
    }

    private fun mapToResult(response: LrclibResponse): LyricsResult? {
        val plain = response.plainLyrics?.takeIf { it.isNotBlank() }
        val synced = response.syncedLyrics?.takeIf { it.isNotBlank() }

        if (plain == null && synced == null) {
            return null
        }

        return LyricsResult(plainLyrics = plain, syncedLyrics = synced)
    }

    @Serializable
    private data class LrclibResponse(
        @SerialName("plainLyrics") val plainLyrics: String? = null,
        @SerialName("syncedLyrics") val syncedLyrics: String? = null
    )
}
