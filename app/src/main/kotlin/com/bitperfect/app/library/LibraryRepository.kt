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

open class LibraryRepository(private val context: Context) {

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

        return recentAlbumsMap.values.toList().takeLast(limit).reversed()
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

        return recentAlbumsMap.values.toList().takeLast(limit).reversed()
    }

    open fun appendNewRelease(outputFolderUriString: String?, albumId: Long, albumTitle: String, artist: String) {
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
            }

            context.contentResolver.openOutputStream(recentFile.uri, "wa")?.use { out ->
                out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    open fun getLibrary(outputFolderUriString: String?): List<ArtistInfo> {
        if (outputFolderUriString.isNullOrBlank()) {
            return emptyList()
        }

        // Decode the SAF URI (e.g. content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBitPerfect)
        val decodedUri = URLDecoder.decode(outputFolderUriString, "UTF-8")

        // Extract substring after the last ':'
        val pathIndex = decodedUri.lastIndexOf(":")
        if (pathIndex == -1 || pathIndex == decodedUri.length - 1) {
            return emptyList()
        }

        var relativePath = decodedUri.substring(pathIndex + 1)
        // Ensure no trailing slash
        if (relativePath.endsWith("/")) {
            relativePath = relativePath.dropLast(1)
        }
        // Ensure no leading slash
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.drop(1)
        }

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
        }
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

        val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        val selectionArgs = arrayOf(albumId.toString())
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
                if (dataPath != null) {
                    val lastSlash = dataPath.lastIndexOf('/')
                    val folderName = if (lastSlash != -1) {
                        val withoutFile = dataPath.substring(0, lastSlash)
                        val folderSlash = withoutFile.lastIndexOf('/')
                        if (folderSlash != -1) withoutFile.substring(folderSlash + 1) else withoutFile
                    } else null

                    if (folderName != null) {
                        val verificationMap = folderVerificationCache.getOrPut(folderName) {
                            var map = emptyMap<String, Boolean>()
                            if (!outputFolderUriString.isNullOrBlank()) {
                                try {
                                    val treeUri = Uri.parse(outputFolderUriString)
                                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                    val albumDoc = rootDoc?.findFile(folderName)
                                    if (albumDoc != null) {
                                        map = readVerificationMapForFolder(albumDoc)
                                    }
                                } catch (e: Exception) {
                                    // ignore uri parsing errors
                                }
                            }
                            map
                        }
                        val key = "$discNumber-$baseTrackNumber"
                        isVerified = verificationMap[key] ?: false
                    }
                }

                tracks.add(TrackInfo(id, title, baseTrackNumber, durationMs, discNumber, albumId, albumTitle, artist, isVerified, dataPath))
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

        val selection = "${MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(trackId.toString())

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
                if (dataPath != null) {
                    val lastSlash = dataPath.lastIndexOf('/')
                    val folderName = if (lastSlash != -1) {
                        val withoutFile = dataPath.substring(0, lastSlash)
                        val folderSlash = withoutFile.lastIndexOf('/')
                        if (folderSlash != -1) withoutFile.substring(folderSlash + 1) else withoutFile
                    } else null

                    if (folderName != null) {
                        var verificationMap = emptyMap<String, Boolean>()
                        if (!outputFolderUriString.isNullOrBlank()) {
                            try {
                                val treeUri = Uri.parse(outputFolderUriString)
                                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                val albumDoc = rootDoc?.findFile(folderName)
                                if (albumDoc != null) {
                                    verificationMap = readVerificationMapForFolder(albumDoc)
                                }
                            } catch (e: Exception) {
                                // ignore uri parsing errors
                            }
                        }
                        val key = "$discNumber-$baseTrackNumber"
                        isVerified = verificationMap[key] ?: false
                    }
                }

                return TrackInfo(id, title, baseTrackNumber, durationMs, discNumber, albumId, albumTitle, artist, isVerified)
            }
        }

        return null
    }
}
