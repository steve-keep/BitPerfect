package com.bitperfect.app.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.net.URLDecoder
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import com.bitperfect.app.usb.MediaScannerHelper

open class LibraryRepository(private val context: Context) {

    open val onLibraryUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    open fun getRecentlyPlayedAlbums(outputFolderUriString: String?, limit: Int = 10): List<Pair<ArtistInfo, AlbumInfo>> {
        if (outputFolderUriString.isNullOrBlank()) {
            return emptyList()
        }

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) {
            null
        }

        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
            return emptyList()
        }

        val recentFile = try {
             parentDir.findFile("recently-played.jsonl")
        } catch (e: Exception) { null } ?: return emptyList()

        // Keep track of albums using a LinkedHashMap to preserve insertion order (updating existing entries to move them to the end)
        val recentAlbumsMap = LinkedHashMap<Long, Pair<ArtistInfo, AlbumInfo>>()

        try {
            context.contentResolver.openInputStream(recentFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            val albumId = json.getLong("albumId")
                            val albumTitle = json.getString("albumTitle")
                            val artistName = json.getString("artist")

                            val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")
                            val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)

                            val albumInfo = AlbumInfo(albumId, albumTitle, artUri)
                            val artistInfo = ArtistInfo(albumId, artistName, listOf(albumInfo))

                            // Remove it if it exists so we can re-add it at the end (most recent)
                            recentAlbumsMap.remove(albumId)
                            recentAlbumsMap[albumId] = Pair(artistInfo, albumInfo)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val validAlbumIds = getLibrary(outputFolderUriString).flatMap { it.albums }.map { it.id }.toSet()
        return recentAlbumsMap.values.filter { validAlbumIds.contains(it.second.id) }.takeLast(limit).reversed()
    }

    open fun getLatestRippedAlbums(outputFolderUriString: String?, limit: Int = 10): List<Pair<ArtistInfo, AlbumInfo>> {
        if (outputFolderUriString.isNullOrBlank()) {
            return emptyList()
        }

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) {
            null
        }

        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
            return emptyList()
        }

        val recentFile = try {
             parentDir.findFile("new-releases.jsonl")
        } catch (e: Exception) { null } ?: return emptyList()

        val recentAlbumsMap = LinkedHashMap<Long, Pair<ArtistInfo, AlbumInfo>>()

        try {
            context.contentResolver.openInputStream(recentFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            val albumId = json.getLong("albumId")
                            val albumTitle = json.getString("albumTitle")
                            val artistName = json.getString("artist")

                            val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")
                            val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)

                            val albumInfo = AlbumInfo(albumId, albumTitle, artUri)
                            val artistInfo = ArtistInfo(albumId, artistName, listOf(albumInfo))

                            recentAlbumsMap.remove(albumId)
                            recentAlbumsMap[albumId] = Pair(artistInfo, albumInfo)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val validAlbumIds = getLibrary(outputFolderUriString).flatMap { it.albums }.map { it.id }.toSet()
        return recentAlbumsMap.values.filter { validAlbumIds.contains(it.second.id) }.takeLast(limit).reversed()
    }

    private fun readFlacTags(filePath: String): Map<String, String> {
        val file = File(filePath)
        if (!file.exists()) return emptyMap()

        return try {
            val f = AudioFileIO.read(file)
            val tag = f.tag
            val tagMap = mutableMapOf<String, String>()

            if (tag is FlacTag && tag.vorbisCommentTag != null) {
                val vorbis = tag.vorbisCommentTag
                val it = vorbis.fields
                while (it.hasNext()) {
                    val field = it.next()
                    tagMap[field.id.uppercase()] = field.toString()
                }
            } else if (tag is VorbisCommentTag) {
                val it = tag.fields
                while (it.hasNext()) {
                    val field = it.next()
                    tagMap[field.id.uppercase()] = field.toString()
                }
            }
            tagMap
        } catch (e: Exception) {
            emptyMap()
        }
    }

    open fun getTrackFlacTags(trackId: Long): Map<String, String> {
        val track = getTrack(trackId, null) ?: return emptyMap()
        val dataPath = track.dataPath ?: return emptyMap()
        return readFlacTags(dataPath)
    }

    open fun appendNewRelease(outputFolderUriString: String?, albumId: Long, albumTitle: String, artist: String, trackId: Long? = null) {
        if (outputFolderUriString.isNullOrBlank()) return

        val parentDir = DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) return

        val recentFile = parentDir.findFile("new-releases.jsonl") ?: parentDir.createFile("application/x-ndjson", "new-releases.jsonl")
        if (recentFile == null) return

        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("albumId", albumId)
                put("albumTitle", albumTitle)
                put("artist", artist)

                if (trackId != null) {
                    put("trackId", trackId)
                    val tags = getTrackFlacTags(trackId)
                    tags["GENRE"]?.let { put("genre", it) }
                    tags["DATE"]?.let { put("year", it) }
                    tags["ALBUMARTIST"]?.let { put("albumArtist", it) }
                    tags["MUSICBRAINZ_ALBUMID"]?.let { put("mbAlbumId", it) }

                    val styleTags = mutableListOf<String>()
                    for ((key, value) in tags) {
                        if (key == "STYLE" || key == "GENRE") {
                            styleTags.add(value)
                        }
                    }
                    if (styleTags.isNotEmpty()) {
                        val jsonArray = org.json.JSONArray()
                        styleTags.distinct().forEach { jsonArray.put(it) }
                        put("tags", jsonArray)
                    }

                }
            }

            context.contentResolver.openOutputStream(recentFile.uri, "wa")?.use { out ->
                out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            MediaScannerHelper.scanSafUri(context, recentFile.uri)
            onLibraryUpdated.tryEmit(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getRelativePathFromUri(outputFolderUriString: String?): String? {
        if (outputFolderUriString.isNullOrBlank()) return null
        val decodedUri = URLDecoder.decode(outputFolderUriString, "UTF-8")
        val pathIndex = decodedUri.lastIndexOf(":")
        if (pathIndex == -1 || pathIndex == decodedUri.length - 1) {
            return null
        }
        var relativePath = decodedUri.substring(pathIndex + 1)
        if (relativePath.endsWith("/")) {
            relativePath = relativePath.dropLast(1)
        }
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.drop(1)
        }
        return relativePath
    }

    open fun getTotalTracks(outputFolderUriString: String?): Int {
        if (outputFolderUriString.isNullOrBlank()) {
            return 0
        }

        val relativePath = getRelativePathFromUri(outputFolderUriString) ?: return 0

        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$relativePath/%")

        var total = 0
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            total = cursor.count
        }
        return total
    }

    private fun normalizeArtistNameForSort(name: String): String {
        val lower = name.lowercase().trim()
        return when {
            lower.startsWith("the ") -> lower.substring(4)
            lower.startsWith("a ") -> lower.substring(2)
            lower.startsWith("an ") -> lower.substring(3)
            else -> lower
        }
    }


    open fun getListeningStatistics(outputFolderUriString: String?): ListeningStats? {
        if (outputFolderUriString.isNullOrBlank()) {
            return null
        }

        val parentDir = try {
            DocumentFile.fromTreeUri(context, Uri.parse(outputFolderUriString))
        } catch (e: Exception) {
            null
        }

        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
            return null
        }

        val recentFile = try {
             parentDir.findFile("recently-played.jsonl")
        } catch (e: Exception) { null } ?: return null

        var totalTimeListenedMs = 0L
        val artistPlayCounts = mutableMapOf<String, Int>()
        // Top tracks counting Map<Pair<Title, Artist>, Count>
        val allTimeSongs = mutableMapOf<Pair<String, String>, Int>()
        val thisMonthSongs = mutableMapOf<Pair<String, String>, Int>()
        val thisYearSongs = mutableMapOf<Pair<String, String>, Int>()

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val currentMonth = now.monthValue
        val currentYear = now.year

        var artistArtUriFallback = mutableMapOf<String, Uri>()

        try {
            context.contentResolver.openInputStream(recentFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.isBlank()) continue
                        try {
                            val json = JSONObject(line!!)
                            val timestamp = json.optLong("timestamp", 0L)
                            val durationMs = json.optLong("durationMs", 0L)
                            val artistName = json.optString("artist")
                            val trackTitle = json.optString("trackTitle")
                            val albumId = json.optLong("albumId", -1L)

                            if (artistName.isNotEmpty() && trackTitle.isNotEmpty()) {
                                totalTimeListenedMs += durationMs
                                artistPlayCounts[artistName] = artistPlayCounts.getOrDefault(artistName, 0) + 1

                                val songKey = Pair(trackTitle, artistName)
                                allTimeSongs[songKey] = allTimeSongs.getOrDefault(songKey, 0) + 1

                                if (timestamp > 0) {
                                    val playDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                                    if (playDate.year == currentYear) {
                                        thisYearSongs[songKey] = thisYearSongs.getOrDefault(songKey, 0) + 1
                                        if (playDate.monthValue == currentMonth) {
                                            thisMonthSongs[songKey] = thisMonthSongs.getOrDefault(songKey, 0) + 1
                                        }
                                    }
                                }

                                if (albumId != -1L && !artistArtUriFallback.containsKey(artistName)) {
                                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")
                                    artistArtUriFallback[artistName] = ContentUris.withAppendedId(albumArtBaseUri, albumId)
                                }
                            }
                        } catch (e: Exception) {
                            // ignore malformed line
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        val topArtistEntry = artistPlayCounts.entries.maxByOrNull { it.value }
        val topArtist = topArtistEntry?.let { entry ->
            var thumbUriStr = getArtistThumbnailUrl(entry.key, outputFolderUriString)
            val thumbUri = if (thumbUriStr != null) Uri.parse(thumbUriStr) else artistArtUriFallback[entry.key]
            TopArtist(entry.key, entry.value, thumbUri)
        }

        val topSongsAllTime = allTimeSongs.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { TopSong(it.key.first, it.key.second, it.value) }

        val topSongsThisMonth = thisMonthSongs.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { TopSong(it.key.first, it.key.second, it.value) }

        val topSongsThisYear = thisYearSongs.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { TopSong(it.key.first, it.key.second, it.value) }

        return ListeningStats(
            mostListenedArtist = topArtist,
            totalTimeListenedMs = totalTimeListenedMs,
            topSongsAllTime = topSongsAllTime,
            topSongsThisMonth = topSongsThisMonth,
            topSongsThisYear = topSongsThisYear
        )
    }

    open fun getLibrary(outputFolderUriString: String?): List<ArtistInfo> {
        if (outputFolderUriString.isNullOrBlank()) {
            return emptyList()
        }

        val relativePath = getRelativePathFromUri(outputFolderUriString) ?: return emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM
        )

        // Add trailing % for LIKE query
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        // Need a trailing slash to match a directory and everything under it
        val selectionArgs = arrayOf("$relativePath/%")

        val sortOrder = "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC"

        val albumsByArtist = mutableMapOf<Long, MutableMap<Long, AlbumInfo>>()
        val artistNames = mutableMapOf<Long, String>()

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)

            val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

            while (cursor.moveToNext()) {
                val artistId = cursor.getLong(artistIdCol)
                val artistName = cursor.getString(artistCol) ?: "Unknown Artist"
                val albumId = cursor.getLong(albumIdCol)
                val albumTitle = cursor.getString(albumCol) ?: "Unknown Album"

                artistNames[artistId] = artistName

                val artistAlbums = albumsByArtist.getOrPut(artistId) { mutableMapOf() }
                if (!artistAlbums.containsKey(albumId)) {
                    val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)
                    artistAlbums[albumId] = AlbumInfo(albumId, albumTitle, artUri)
                }
            }
        }

        return albumsByArtist.map { (artistId, albumsMap) ->
            ArtistInfo(
                id = artistId,
                name = artistNames[artistId] ?: "Unknown Artist",
                albums = albumsMap.values.toList()
            )
        }.sortedBy { normalizeArtistNameForSort(it.name) }
    }

    open fun getArtistThumbnailUrl(artistName: String, outputFolderUriString: String?): String? {
        if (outputFolderUriString.isNullOrBlank()) {
            return null
        }

        val parentDir = try {
            val rootUri = Uri.parse(outputFolderUriString)
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
        } catch (e: Exception) { null } ?: return null

        val safeArtist = artistName.replace("/", "_")
        val artistDir = parentDir.findFile(safeArtist) ?: return null
        val artistJsonFile = artistDir.findFile("artist.json") ?: return null

        try {
            context.contentResolver.openInputStream(artistJsonFile.uri)?.use { inputStream ->
                val jsonString = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
                val jsonObject = org.json.JSONObject(jsonString)
                val artistsArray = jsonObject.optJSONArray("artists")
                if (artistsArray != null && artistsArray.length() > 0) {
                    val firstArtist = artistsArray.getJSONObject(0)
                    return firstArtist.optString("strArtistThumb", null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }


    open fun getArtistBio(artistName: String, outputFolderUriString: String?): String? {
        if (outputFolderUriString.isNullOrBlank()) {
            return null
        }

        val parentDir = try {
            val rootUri = Uri.parse(outputFolderUriString)
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, rootUri)
        } catch (e: Exception) { null } ?: return null

        val safeArtist = artistName.replace("/", "_")
        val artistDir = parentDir.findFile(safeArtist) ?: return null
        val artistJsonFile = artistDir.findFile("artist.json") ?: return null

        try {
            context.contentResolver.openInputStream(artistJsonFile.uri)?.use { inputStream ->
                val jsonString = java.io.BufferedReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
                val jsonObject = org.json.JSONObject(jsonString)
                val artistsArray = jsonObject.optJSONArray("artists")
                if (artistsArray != null && artistsArray.length() > 0) {
                    val firstArtist = artistsArray.getJSONObject(0)
                    return firstArtist.optString("strBiography", null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    open fun getTracksForAlbum(albumId: Long, outputFolderUriString: String?): List<TrackInfo> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA
        )

        val relativePath = getRelativePathFromUri(outputFolderUriString)

        val selection = if (relativePath != null) {
            "${MediaStore.Audio.Media.ALBUM_ID} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        }
        val selectionArgs = if (relativePath != null) {
            arrayOf(albumId.toString(), "$relativePath/%")
        } else {
            arrayOf(albumId.toString())
        }
        val sortOrder = "${MediaStore.Audio.Media.TRACK} ASC"

        val tracks = mutableListOf<TrackInfo>()

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            // Cache verification status per folder to avoid reading the jsonl file multiple times for the same album
            val folderVerificationCache = mutableMapOf<String, Map<String, Boolean>>()

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Track"
                val rawTrackNumber = cursor.getInt(trackCol)
                val durationMs = cursor.getLong(durationCol)
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val albumTitle = cursor.getString(albumCol) ?: "Unknown Album"
                val dataPath = cursor.getString(dataCol)

                val baseTrackNumber = if (rawTrackNumber >= 1000) rawTrackNumber % 1000 else rawTrackNumber
                val discNumber = if (rawTrackNumber >= 1000) rawTrackNumber / 1000 else 1

                var isVerified = false
                if (dataPath != null && !outputFolderUriString.isNullOrBlank()) {
                    val parts = dataPath.trimEnd('/').split('/')
                    val albumFolderName = if (parts.size >= 2) parts[parts.size - 2] else null
                    val artistFolderName = if (parts.size >= 3) parts[parts.size - 3] else null

                    if (albumFolderName != null && artistFolderName != null) {
                        val cacheKey = "$artistFolderName/$albumFolderName"
                        val verificationMap = folderVerificationCache.getOrPut(cacheKey) {
                            try {
                                val treeUri = Uri.parse(outputFolderUriString)
                                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                val artistDoc = rootDoc?.findFile(artistFolderName)
                                val albumDoc = artistDoc?.findFile(albumFolderName)
                                if (albumDoc != null) readVerificationMapForFolder(albumDoc) else emptyMap()
                            } catch (e: Exception) {
                                emptyMap()
                            }
                        }
                        val key = "$discNumber-$baseTrackNumber"
                        isVerified = verificationMap[key] ?: false
                    }
                }

                tracks.add(TrackInfo(id, title, baseTrackNumber, durationMs, discNumber, albumId, albumTitle, artist, isVerified, dataPath, dataPath))
            }
        }

        return tracks
    }

    private fun readVerificationMapForFolder(albumDir: DocumentFile): Map<String, Boolean> {
        val verificationMap = mutableMapOf<String, Boolean>()
        try {
            val jsonlFile = albumDir.findFile("BitPerfect.jsonl") ?: return verificationMap
            context.contentResolver.openInputStream(jsonlFile.uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isNotBlank()) {
                            try {
                                val obj = JSONObject(line)
                                val disc = obj.optInt("disc", -1)
                                val track = obj.optInt("track", -1)
                                val accurateRip = obj.optJSONObject("accurateRip")
                                val isVerified = accurateRip?.optBoolean("isVerified", false) ?: false
                                if (disc != -1 && track != -1) {
                                    verificationMap["$disc-$track"] = isVerified
                                }
                            } catch (e: Exception) { /* ignore malformed lines */ }
                        }
                    }
                }
            }
        } catch (e: Exception) { /* ignore */ }
        return verificationMap
    }

    open fun getTrack(trackId: Long, outputFolderUriString: String?): TrackInfo? {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA
        )

        val relativePath = getRelativePathFromUri(outputFolderUriString)

        val selection = if (relativePath != null) {
            "${MediaStore.Audio.Media._ID} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Audio.Media._ID} = ?"
        }
        val selectionArgs = if (relativePath != null) {
            arrayOf(trackId.toString(), "$relativePath/%")
        } else {
            arrayOf(trackId.toString())
        }

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown Track"
                val rawTrackNumber = cursor.getInt(trackCol)
                val durationMs = cursor.getLong(durationCol)
                val albumId = cursor.getLong(albumIdCol)
                val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                val albumTitle = cursor.getString(albumCol) ?: "Unknown Album"
                val dataPath = cursor.getString(dataCol)

                val baseTrackNumber = if (rawTrackNumber >= 1000) rawTrackNumber % 1000 else rawTrackNumber
                val discNumber = if (rawTrackNumber >= 1000) rawTrackNumber / 1000 else 1

                var isVerified = false
                if (dataPath != null && !outputFolderUriString.isNullOrBlank()) {
                    val parts = dataPath.trimEnd('/').split('/')
                    val albumFolderName = if (parts.size >= 2) parts[parts.size - 2] else null
                    val artistFolderName = if (parts.size >= 3) parts[parts.size - 3] else null

                    if (albumFolderName != null && artistFolderName != null) {
                        val verificationMap = try {
                            val treeUri = Uri.parse(outputFolderUriString)
                            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                            val artistDoc = rootDoc?.findFile(artistFolderName)
                            val albumDoc = artistDoc?.findFile(albumFolderName)
                            if (albumDoc != null) readVerificationMapForFolder(albumDoc) else emptyMap()
                        } catch (e: Exception) {
                            emptyMap()
                        }
                        val key = "$discNumber-$baseTrackNumber"
                        isVerified = verificationMap[key] ?: false
                    }
                }

                return TrackInfo(id, title, baseTrackNumber, durationMs, discNumber, albumId, albumTitle, artist, isVerified, dataPath, dataPath)
            }
        }

        return null
    }
}
