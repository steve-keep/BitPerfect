package com.bitperfect.app.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.net.URLDecoder

class LibraryRepository(private val context: Context) {

    fun getLibrary(outputFolderUriString: String?): List<ArtistInfo> {
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

        val albumsByArtist = mutableMapOf<Long, MutableMap<String, AlbumInfo>>()
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
                val albumTitleLower = albumTitle.lowercase()
                if (!artistAlbums.containsKey(albumTitleLower)) {
                    val artUri = ContentUris.withAppendedId(albumArtBaseUri, albumId)
                    artistAlbums[albumTitleLower] = AlbumInfo(albumId, albumTitle, artUri)
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
}
