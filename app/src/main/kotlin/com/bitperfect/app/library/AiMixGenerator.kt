package com.bitperfect.app.library

import android.content.Context
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadStatus
import org.json.JSONArray
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.guava.await

enum class NanoStatus {
    AVAILABLE,
    DOWNLOADING,
    DOWNLOADABLE,
    UNAVAILABLE
}

class AiMixGenerator() {

    suspend fun checkNanoStatus(): NanoStatus {
        return try {
            val model = Generation.getClient()
            try {
                when (model.checkStatus()) {
                    FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
                    FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
                    FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
                    else -> NanoStatus.UNAVAILABLE
                }
            } finally {
                model.close()
            }
        } catch (e: Exception) {
            println("AiMixGenerator checkNanoStatus error: ${e::class.simpleName}: ${e.message}")
            NanoStatus.UNAVAILABLE
        }
    }

    suspend fun isAvailable(context: Context): Boolean = checkNanoStatus() == NanoStatus.AVAILABLE

    /**
     * Triggers download if DOWNLOADABLE. Collects progress via the provided callback.
     * Returns true if download completed successfully, false otherwise.
     */
    suspend fun downloadNano(onProgress: (bytesDownloaded: Long) -> Unit = {}): Boolean {
        return try {
            val model = Generation.getClient()
            var success = false
            try {
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            println("AiMixGenerator: Nano download started")
                        }
                        is DownloadStatus.DownloadProgress -> {
                            println("AiMixGenerator: Nano download ${status.totalBytesDownloaded} bytes")
                            onProgress(status.totalBytesDownloaded)
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            println("AiMixGenerator: Nano download complete")
                            success = true
                        }
                        is DownloadStatus.DownloadFailed -> {
                            println("AiMixGenerator: Nano download failed: ${status.e.message}")
                            success = false
                        }
                    }
                }
            } finally {
                model.close()
            }
            success
        } catch (e: Exception) {
            println("AiMixGenerator downloadNano error: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    suspend fun generateMixes(
        context: Context,
        librarySummary: String,
        availableTracks: List<AiMixTrack>
    ): List<AiMix> {
        return try {
            val prompt = """
                You are a music curator for an audiophile. Based on the following music library,
                create 5 interesting and varied playlists. Be creative with the names and
                descriptions — surprise the user. Consider energy flow, harmonic compatibility
                (Camelot keys), genre, era, and mood.

                $librarySummary

                Respond ONLY with a JSON array. No preamble, no explanation, no markdown.
                Each element:
                {
                  "name": "Creative mix name",
                  "description": "One sentence describing the vibe",
                  "trackTitles": ["Title 1", "Title 2", ...]
                }

                Rules:
                - Each mix should have 8-15 tracks
                - All track titles must exist exactly in the library summary above
                - Vary the mixes — no two should have the same energy or genre focus
                - At least one mix should sequence tracks for harmonic compatibility using Camelot keys
                - At least one mix should focus on AccurateRip verified tracks only
            """.trimIndent()

            val model = Generation.getClient()

            val response = model.generateContent(prompt) as GenerateContentResponse

            var text: String = response.candidates.firstOrNull()?.text ?: return emptyList()

            text = text.trim()
            if (text.startsWith("```json")) {
                text = text.substring("```json".length)
            } else if (text.startsWith("```")) {
                text = text.substring("```".length)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - "```".length)
            }
            text = text.trim()

            val jsonArray = JSONArray(text)
            val mixes = mutableListOf<AiMix>()
            val currentTime = System.currentTimeMillis()

            for (i in 0 until jsonArray.length()) {
                val mixObj = jsonArray.getJSONObject(i)
                val name = mixObj.optString("name", "Untitled Mix")
                val description = mixObj.optString("description", "")
                val trackTitlesArray = mixObj.optJSONArray("trackTitles")

                val mixTracks = mutableListOf<AiMixTrack>()
                if (trackTitlesArray != null) {
                    for (j in 0 until trackTitlesArray.length()) {
                        val title = trackTitlesArray.optString(j)
                        if (title.isNotBlank()) {
                            val matchedTrack = availableTracks.find { it.title.equals(title, ignoreCase = true) }
                            if (matchedTrack != null) {
                                mixTracks.add(matchedTrack)
                            }
                        }
                    }
                }

                if (mixTracks.isNotEmpty()) {
                    mixes.add(
                        AiMix(
                            generatedAt = currentTime,
                            name = name,
                            description = description,
                            tracks = mixTracks
                        )
                    )
                }
            }
            model.close()
            mixes
        } catch (e: Exception) {
            println("AiMixGenerator generateMixes error: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
