package com.bitperfect.core.services

import android.content.Context
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.utils.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArtworkResolverImpl(private val context: Context) : ArtworkResolver {
    private val TAG = "ArtworkResolverImpl"
    private val itunesArtworkRepository = ItunesArtworkRepository(context)

    private val client = HttpClient(OkHttp) {
        // OkHttp handles redirects (including 307) automatically
        defaultRequest {
            header("User-Agent", "BitPerfect/1.0 (https://github.com/steve-keep/BitPerfect)")
        }
    }

    override suspend fun resolveArtwork(metadata: DiscMetadata): ResolvedArtwork? = withContext(Dispatchers.IO) {
        if (metadata.mbReleaseId.isNotBlank()) {
            val caaUrl = "https://coverartarchive.org/release/${metadata.mbReleaseId}/front"
            try {
                AppLogger.d(TAG, "Checking Cover Art Archive: $caaUrl")
                val response = client.head(caaUrl)
                if (response.status == HttpStatusCode.OK) {
                    AppLogger.d(TAG, "Cover Art Archive found artwork for release ${metadata.mbReleaseId}")
                    val previewUrl = "https://coverartarchive.org/release/${metadata.mbReleaseId}/front-500"
                    val highResUrl = caaUrl
                    return@withContext ResolvedArtwork(previewUrl, highResUrl)
                } else {
                    AppLogger.d(TAG, "Cover Art Archive returned ${response.status} for release ${metadata.mbReleaseId}")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error checking Cover Art Archive: ${e.message}")
            }
        } else {
            AppLogger.d(TAG, "No valid mbReleaseId found for CAA lookup.")
        }

        // Fallback to iTunes
        val expectedTrackCount = metadata.trackTitles.size.takeIf { it > 0 }
        AppLogger.d(TAG, "Falling back to iTunes artwork search for '${metadata.artistName}' - '${metadata.albumTitle}'")
        return@withContext itunesArtworkRepository.fetchItunesArtwork(metadata.artistName, metadata.albumTitle, expectedTrackCount)
    }
}
