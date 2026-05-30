package com.bitperfect.core.services

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
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.IOException

class AudioDbRepository() {
    private val TAG = "AudioDbRepository"

    private var client: HttpClient = sharedClient

    internal constructor(engine: HttpClientEngine) : this() {
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

    suspend fun fetchArtist(artistName: String): String? = withContext(Dispatchers.IO) {
        try {
            AppLogger.d(TAG, "Fetching artist data for: $artistName")

            val normalisedName = artistName
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')

            val url = "https://www.theaudiodb.com/api/v1/json/2/search.php"
            val httpResponse = client.get(url) {
                parameter("s", normalisedName)
            }

            AppLogger.d(TAG, "HTTP ${httpResponse.status.value}")

            if (httpResponse.status != HttpStatusCode.OK) {
                AppLogger.w(TAG, "AudioDb API returned non-200 status: ${httpResponse.status}")
                return@withContext null
            }

            val body = httpResponse.bodyAsText()
            if (body.isBlank()) {
                AppLogger.w(TAG, "AudioDb API returned empty body")
                return@withContext null
            }

            val hasResults = try {
                JSONObject(body).optJSONArray("artists") != null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }

            if (hasResults) {
                AppLogger.d(TAG, "Successfully fetched artist data")
                return@withContext body
            } else {
                AppLogger.d(TAG, "No artists found in response")
                return@withContext null
            }
        } catch (e: CancellationException) {
            AppLogger.w(TAG, "AudioDb fetch cancelled")
            throw e
        } catch (e: IOException) {
            AppLogger.e(TAG, "Network error: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unknown error: ${e.message}")
            return@withContext null
        }
    }

    companion object {
        private val sharedJson = Json { ignoreUnknownKeys = true }

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
