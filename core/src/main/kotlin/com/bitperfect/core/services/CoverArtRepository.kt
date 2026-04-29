package com.bitperfect.core.services

import android.content.Context
import android.util.Log
import com.bitperfect.core.utils.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CoverArtRepository(private val context: Context) {
    private val TAG = "CoverArtRepository"

    private var client: HttpClient

    init {
        client = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    followRedirects(false)
                }
            }
        }
    }

    internal constructor(context: Context, engine: HttpClientEngine) : this(context) {
        client = HttpClient(engine) {
            expectSuccess = false
            followRedirects = false // Set this on the HttpClientConfig for MockEngine to prevent following redirects!
        }
    }

    suspend fun getCoverArtUrl(mbReleaseId: String): String? = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "caa_${mbReleaseId}.txt")

        if (cacheFile.exists()) {
            val cachedUrl = cacheFile.readText().trim()
            if (cachedUrl.isNotEmpty()) {
                AppLogger.d(TAG, "Cache hit for $mbReleaseId: $cachedUrl")
                return@withContext cachedUrl
            }
        }

        try {
            val requestUrl = "https://coverartarchive.org/release/$mbReleaseId/front"
            val response: HttpResponse = client.get(requestUrl)

            val status = response.status.value
            AppLogger.d(TAG, "Network response status: $status")

            val resolvedUrl = when {
                status in 300..399 -> {
                    response.headers[HttpHeaders.Location]
                }
                status == 200 -> {
                    requestUrl
                }
                else -> {
                    if (status != 404) {
                        AppLogger.e(TAG, "Unexpected HTTP status $status for $mbReleaseId")
                    }
                    null
                }
            }

            if (resolvedUrl != null) {
                cacheFile.writeText(resolvedUrl)
                AppLogger.d(TAG, "Resolved cover art for $mbReleaseId: $resolvedUrl")
            } else {
                AppLogger.e(TAG, "Resolved URL is null, headers: ${response.headers}")
            }

            return@withContext resolvedUrl

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error fetching cover art for $mbReleaseId: ${Log.getStackTraceString(e)}")
            return@withContext null
        }
    }
}
