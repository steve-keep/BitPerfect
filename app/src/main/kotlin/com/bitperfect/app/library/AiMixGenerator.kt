package com.bitperfect.app.library

import android.content.Context
import com.google.mlkit.genai.generative.GenerativeModel
import org.json.JSONArray

class AiMixGenerator(private val context: Context) {

    suspend fun isAvailable(): Boolean {
        return try {
            GenerativeModel.isAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun generateMixes(librarySummary: String, availableTracks: List<AiMixTrack>): List<AiMix> {
        return try {
            val prompt = """
                You are a music curator for an audiophile. Based on the following music library, create 5
                interesting and varied playlists. Be creative with the names and descriptions — surprise the user.
                Consider energy flow, harmonic compatibility (Camelot keys), genre, era, and mood.

                $librarySummary

                Respond ONLY with a JSON array. No preamble, no explanation, no markdown. Each element:
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

            val model = GenerativeModel.getInstance(context)
            val response = model.generateContent(prompt)
            var text = response.text ?: return emptyList()

            // Strip any markdown fences
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
            mixes
        } catch (e: Exception) {
            emptyList()
        }
    }
}
