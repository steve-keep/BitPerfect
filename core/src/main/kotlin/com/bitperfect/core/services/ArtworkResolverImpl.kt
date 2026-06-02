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
        // 1. Trust MusicBrainz! No slow HTTP HEAD request needed.
        if (metadata.mbReleaseId.isNotBlank() && metadata.hasFrontCoverArt) {
            AppLogger.d(TAG, "MusicBrainz indicates Cover Art Archive has front artwork for ${metadata.mbReleaseId}")
            val url = "https://coverartarchive.org/release/${metadata.mbReleaseId}/front"
            return@withContext ResolvedArtwork(url)
        }

        // 2. Fallback to iTunes instantly
        AppLogger.d(TAG, "No Cover Art Archive artwork. Falling back to iTunes artwork search for '${metadata.artistName}' - '${metadata.albumTitle}'")
        val expectedTrackCount = metadata.trackTitles.size.takeIf { it > 0 }
        return@withContext itunesArtworkRepository.fetchItunesArtwork(metadata.artistName, metadata.albumTitle, expectedTrackCount)
    }
}
