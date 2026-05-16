package com.bitperfect.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class AiMixTrack(
    val trackId: Long,
    val artist: String,
    val title: String,
    val albumTitle: String,
    val albumId: Long
)

data class AiMix(
    val generatedAt: Long,
    val name: String,
    val description: String,
    val tracks: List<AiMixTrack>
)

class AiMixRepository {

    fun getLatestMixes(context: Context, outputFolderUriString: String?, limit: Int = 5): List<AiMix> {
        if (outputFolderUriString.isNullOrBlank()) return emptyList()

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) { null } ?: return emptyList()

        if (!parentDir.exists() || !parentDir.isDirectory) return emptyList()

        val mixesFile = parentDir.findFile("ai-mixes.jsonl") ?: return emptyList()

        val mixes = mutableListOf<AiMix>()

        try {
            context.contentResolver.openInputStream(mixesFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            val tracksArray = json.getJSONArray("tracks")
                            val tracks = mutableListOf<AiMixTrack>()
                            for (i in 0 until tracksArray.length()) {
                                val trackObj = tracksArray.getJSONObject(i)
                                tracks.add(
                                    AiMixTrack(
                                        trackId = trackObj.getLong("trackId"),
                                        artist = trackObj.getString("artist"),
                                        title = trackObj.getString("title"),
                                        albumTitle = trackObj.getString("albumTitle"),
                                        albumId = trackObj.getLong("albumId")
                                    )
                                )
                            }
                            mixes.add(
                                AiMix(
                                    generatedAt = json.getLong("generatedAt"),
                                    name = json.getString("name"),
                                    description = json.getString("description"),
                                    tracks = tracks
                                )
                            )
                        } catch (e: Exception) {
                            // Skip malformed lines
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return mixes.takeLast(limit).reversed()
    }

    fun getLastGeneratedAt(context: Context, outputFolderUriString: String?): Long? {
        if (outputFolderUriString.isNullOrBlank()) return null

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) { null } ?: return null

        if (!parentDir.exists() || !parentDir.isDirectory) return null

        val mixesFile = parentDir.findFile("ai-mixes.jsonl") ?: return null

        var lastGeneratedAt: Long? = null

        try {
            context.contentResolver.openInputStream(mixesFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            lastGeneratedAt = json.getLong("generatedAt")
                        } catch (e: Exception) {
                            // Skip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return lastGeneratedAt
    }

    fun appendMixes(context: Context, outputFolderUriString: String?, mixes: List<AiMix>) {
        if (outputFolderUriString.isNullOrBlank()) return

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) { null } ?: return

        if (!parentDir.exists() || !parentDir.isDirectory) return

        val mixesFile = parentDir.findFile("ai-mixes.jsonl") ?: parentDir.createFile("application/x-ndjson", "ai-mixes.jsonl")
        if (mixesFile == null) return

        try {
            context.contentResolver.openOutputStream(mixesFile.uri, "wa")?.use { out ->
                for (mix in mixes) {
                    val json = JSONObject().apply {
                        put("generatedAt", mix.generatedAt)
                        put("name", mix.name)
                        put("description", mix.description)
                        val tracksArray = JSONArray()
                        for (track in mix.tracks) {
                            val trackObj = JSONObject().apply {
                                put("trackId", track.trackId)
                                put("artist", track.artist)
                                put("title", track.title)
                                put("albumTitle", track.albumTitle)
                                put("albumId", track.albumId)
                            }
                            tracksArray.put(trackObj)
                        }
                        put("tracks", tracksArray)
                    }
                    out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private data class LibraryEntry(
        val timestamp: Long,
        val artist: String,
        val albumTitle: String,
        val trackTitle: String,
        val year: String?,
        val genre: String?,
        val bpm: Double?,
        val initialKey: String?,
        val energy: Double?,
        val accurateRipVerified: Boolean
    )

    fun buildLibrarySummary(context: Context, outputFolderUriString: String?): String {
        if (outputFolderUriString.isNullOrBlank()) return ""

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) { null } ?: return ""

        if (!parentDir.exists() || !parentDir.isDirectory) return ""

        val entries = mutableMapOf<String, LibraryEntry>()

        val processFile = { filename: String ->
            try {
                val file = parentDir.findFile(filename)
                if (file != null) {
                    context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line!!.isBlank()) continue
                                try {
                                    val json = JSONObject(line!!)
                                    val artist = json.optString("artist", "")
                                    val albumTitle = json.optString("albumTitle", "")
                                    val trackTitle = json.optString("trackTitle", "")
                                    if (artist.isBlank() || albumTitle.isBlank() || trackTitle.isBlank()) continue

                                    val key = "$artist|$albumTitle|$trackTitle"
                                    val timestamp = json.optLong("timestamp", 0)

                                    val existing = entries[key]
                                    if (existing == null || existing.timestamp < timestamp) {
                                        entries[key] = LibraryEntry(
                                            timestamp = timestamp,
                                            artist = artist,
                                            albumTitle = albumTitle,
                                            trackTitle = trackTitle,
                                            year = json.optString("year", null),
                                            genre = json.optString("genre", null),
                                            bpm = if (json.has("bpm")) json.getDouble("bpm") else null,
                                            initialKey = json.optString("initialKey", null),
                                            energy = if (json.has("energy")) json.getDouble("energy") else null,
                                            accurateRipVerified = json.optBoolean("accurateRipVerified", false)
                                        )
                                    }

                                } catch (e: Exception) {
                                    // Skip
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        processFile("recently-played.jsonl")
        processFile("new-releases.jsonl")

        var trackList = entries.values.toList()

        if (trackList.isEmpty()) return ""

        if (trackList.size > 80) {
            val sortedList = trackList.sortedBy { it.energy ?: 0.5 }
            val step = sortedList.size.toDouble() / 80
            val selected = mutableListOf<LibraryEntry>()
            for (i in 0 until 80) {
                val index = (i * step).toInt().coerceIn(0, sortedList.size - 1)
                selected.add(sortedList[index])
            }
            trackList = selected
        }

        val builder = java.lang.StringBuilder()
        builder.appendLine("Library summary (${trackList.size} tracks):")
        for (track in trackList) {
            val yearStr = if (track.year != null && track.year != "null") ", ${track.year}" else ""
            val genreStr = if (track.genre != null && track.genre != "null") " | Genre: ${track.genre}" else ""
            val bpmStr = if (track.bpm != null) " | BPM: ${track.bpm}" else ""
            val keyStr = if (track.initialKey != null && track.initialKey != "null") " | Key: ${track.initialKey}" else ""
            val energyStr = if (track.energy != null) " | Energy: ${track.energy}" else ""
            val verifiedStr = if (track.accurateRipVerified) " | Verified: yes" else ""

            builder.appendLine("- \"${track.trackTitle}\" by ${track.artist} (${track.albumTitle}$yearStr)$genreStr$bpmStr$keyStr$energyStr$verifiedStr")
        }

        return builder.toString().trim()
    }
}
