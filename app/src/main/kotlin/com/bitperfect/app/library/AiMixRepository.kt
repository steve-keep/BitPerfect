package com.bitperfect.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.bitperfect.app.library.TrackCandidate
import com.bitperfect.app.library.BlueprintLoader
import com.bitperfect.app.library.MixEngine

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

    fun refreshMixes(context: Context, outputFolderUriString: String?): List<AiMix> {
        if (outputFolderUriString.isNullOrBlank()) return emptyList()

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) { null } ?: return emptyList()

        if (!parentDir.exists() || !parentDir.isDirectory) return emptyList()

        val entries = mutableMapOf<String, Pair<Long, com.bitperfect.app.library.TrackCandidate>>()

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
                                    val albumTitle = json.optString("albumTitle", "").ifBlank {
                                        json.optString("album", "")
                                    }
                                    val trackTitle = json.optString("trackTitle", "").ifBlank {
                                        json.optString("title", "")
                                    }
                                    if (artist.isBlank() || albumTitle.isBlank() || trackTitle.isBlank()) continue

                                    val key = "$artist|$albumTitle|$trackTitle"
                                    val timestamp = json.optLong("timestamp", 0)

                                    val existing = entries[key]
                                    if (existing == null || existing.first < timestamp) {
                                        val tagsList = mutableListOf<String>()
                                        val tagsArray = json.optJSONArray("tags")
                                        if (tagsArray != null) {
                                            val len = tagsArray?.length() ?: 0
                                        for (i in 0 until len) {
                                                tagsList.add(tagsArray.getString(i))
                                            }
                                        }

                                        val candidate = com.bitperfect.app.library.TrackCandidate(
                                            trackId = json.optLong("trackId", 0L),
                                            artist = artist,
                                            albumTitle = albumTitle,
                                            albumId = json.optLong("albumId", 0L),
                                            trackTitle = trackTitle,
                                            bpm = if (json.has("bpm")) json.getDouble("bpm").toFloat() else null,
                                            initialKey = json.optString("initialKey", null).takeIf { it != "null" && it.isNotBlank() },
                                            energy = if (json.has("energy")) json.getDouble("energy").toFloat() else null,
                                            accurateRipVerified = json.optBoolean("accurateRipVerified", false),
                                            genre = json.optString("genre", null).takeIf { it != "null" && it.isNotBlank() },
                                            year = json.optString("year", null).takeIf { it != "null" && it.isNotBlank() },
                                            tags = tagsList
                                        )
                                        entries[key] = Pair(timestamp, candidate)
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

        val candidates = entries.values.map { it.second }
        val weekSeed = System.currentTimeMillis() / (7L * 24 * 60 * 60 * 1000)
        val blueprints = com.bitperfect.app.library.BlueprintLoader.load(context)

        val mixes = com.bitperfect.app.library.MixEngine().generate(candidates, blueprints, weekSeed)

        // Truncate and overwrite ai-mixes.jsonl
        try {
            var mixesFile = parentDir.findFile("ai-mixes.jsonl")
            if (mixesFile != null) {
                mixesFile.delete()
            }
            mixesFile = parentDir.createFile("application/x-ndjson", "ai-mixes.jsonl")

            if (mixesFile != null) {
                context.contentResolver.openOutputStream(mixesFile.uri, "w")?.use { out ->
                    mixes.forEach { mix ->
                        val json = JSONObject().apply {
                            put("generatedAt", mix.generatedAt as Any)
                            put("name", mix.name as Any)
                            put("description", mix.description as Any)
                            val tracksArray = JSONArray()
                            mix.tracks.forEach { track ->
                                val trackObj = JSONObject().apply {
                                    put("trackId", track.trackId as Any)
                                    put("artist", track.artist as Any)
                                    put("title", track.title as Any)
                                    put("albumTitle", track.albumTitle as Any)
                                    put("albumId", track.albumId as Any)
                                }
                                tracksArray.put(trackObj)
                            }
                            put("tracks", tracksArray)
                        }
                        out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return mixes
    }
}
