package com.bitperfect.core.services

import android.content.Context
import com.bitperfect.core.models.FetchState
import com.bitperfect.core.models.LyricsFetchResult
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.IOException
import kotlin.math.roundToLong

class LyricsRepository(private val context: Context) {
    private val TAG = "LyricsRepository"

    // Allow overriding the client via internal constructor (e.g., for tests)
    private var client: HttpClient = sharedClient

    internal constructor(context: Context, engine: HttpClientEngine) : this(context) {
        client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(sharedJson)
                json(sharedJson, contentType = ContentType.Text.Plain)
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
    ): LyricsFetchResult = withContext(Dispatchers.IO) {
        val logTag = "[Lyrics][Track ${trackNumber.toString().padStart(2, '0')}]"

        if (mbReleaseId.isBlank()) {
            AppLogger.w(TAG, "$logTag Skipped because mbReleaseId is blank")
            return@withContext LyricsFetchResult.Failure(FetchState.INVALID_RESPONSE, "mbReleaseId is blank")
        }

        val cacheFile = File(context.cacheDir, "lyrics_${mbReleaseId}_${trackNumber}.json")

        try {
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 30L * 86400 * 1000) {
                val jsonString = cacheFile.readText()
                val response: LrclibResponse = sharedJson.decodeFromString(jsonString)
                val result = mapToResult(response)
                if (result != null) {
                    AppLogger.d(TAG, "$logTag Loaded from cache")
                    return@withContext LyricsFetchResult.Success(result)
                }
            }
        } catch (e: SerializationException) {
            AppLogger.e(TAG, "$logTag Error parsing cache: ${e.message}")
            try { cacheFile.delete() } catch (ignored: Exception) {}
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e(TAG, "$logTag Error reading from cache: ${e.message}")
        }

        return@withContext performFetch(
            logTag = logTag,
            artistName = artistName,
            albumTitle = albumTitle,
            trackTitle = trackTitle,
            trackNumber = trackNumber,
            durationSeconds = durationSeconds,
            cacheFile = cacheFile,
            isRetry = false
        )
    }

    private suspend fun performFetch(
        logTag: String,
        artistName: String,
        albumTitle: String,
        trackTitle: String,
        trackNumber: Int,
        durationSeconds: Double?,
        cacheFile: File,
        isRetry: Boolean
    ): LyricsFetchResult {
        try {
            AppLogger.d(TAG, "$logTag Request started (duration=${durationSeconds ?: "none"}, retry=$isRetry)")

            val url = "https://lrclib.net/api/get"
            val httpResponse = client.get(url) {
                parameter("artist_name", artistName)
                parameter("track_name", trackTitle)
                parameter("album_name", albumTitle)
                if (durationSeconds != null) {
                    parameter("duration", durationSeconds.roundToLong())
                }
            }

            AppLogger.d(TAG, "$logTag HTTP ${httpResponse.status.value}")

            if (httpResponse.status == HttpStatusCode.NotFound) {
                if (!isRetry && durationSeconds != null) {
                    AppLogger.d(TAG, "$logTag Not found with duration, retrying without duration...")
                    return performFetch(
                        logTag = logTag,
                        artistName = artistName,
                        albumTitle = albumTitle,
                        trackTitle = trackTitle,
                        trackNumber = trackNumber,
                        durationSeconds = null,
                        cacheFile = cacheFile,
                        isRetry = true
                    )
                }
                return LyricsFetchResult.Failure(FetchState.NOT_FOUND, httpCode = httpResponse.status.value)
            } else if (httpResponse.status != HttpStatusCode.OK) {
                AppLogger.w(TAG, "$logTag Lrclib API returned non-200 status: ${httpResponse.status}")
                return LyricsFetchResult.Failure(FetchState.HTTP_ERROR, httpCode = httpResponse.status.value)
            }

            val responseBody = httpResponse.bodyAsText()
            if (responseBody.isBlank()) {
                return LyricsFetchResult.Failure(FetchState.EMPTY_HTTP_BODY)
            }

            val response: LrclibResponse = sharedJson.decodeFromString(responseBody)

            try {
                cacheFile.writeText(responseBody)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG, "$logTag Error writing to cache: ${e.message}")
            }

            val result = mapToResult(response)
            if (result != null) {
                if (result.syncedLyrics != null) {
                    AppLogger.d(TAG, "$logTag Parsed synced lyrics")
                } else if (result.plainLyrics != null) {
                    AppLogger.d(TAG, "$logTag Parsed plain lyrics")
                }
                return LyricsFetchResult.Success(result)
            } else {
                AppLogger.d(TAG, "$logTag Response contained no lyrics")
                return LyricsFetchResult.Failure(FetchState.NO_LYRICS, "Response contained no lyrics")
            }
        } catch (e: CancellationException) {
            AppLogger.w(TAG, "$logTag Lyrics fetch cancelled")
            throw e
        } catch (e: SerializationException) {
            AppLogger.e(TAG, "$logTag JSON Parse error: ${e.message}")
            return LyricsFetchResult.Failure(FetchState.JSON_PARSE_ERROR, e.message, throwable = e)
        } catch (e: IOException) {
            AppLogger.e(TAG, "$logTag Network error: ${e.message}")
            return LyricsFetchResult.Failure(FetchState.NETWORK_ERROR, e.message, throwable = e)
        } catch (e: Exception) {
            AppLogger.e(TAG, "$logTag Unknown error: ${e.message}")
            return LyricsFetchResult.Failure(FetchState.UNKNOWN_ERROR, e.message, throwable = e)
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

    companion object {
        private val sharedJson = Json { ignoreUnknownKeys = true }

        // Single shared HTTP client instance
        private val sharedClient by lazy {
            HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(sharedJson)
                    json(sharedJson, contentType = ContentType.Text.Plain)
                }
                defaultRequest {
                    header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
                }
                expectSuccess = false
            }
        }
    }
}
