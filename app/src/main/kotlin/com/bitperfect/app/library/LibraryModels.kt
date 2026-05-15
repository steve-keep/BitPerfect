package com.bitperfect.app.library

import android.net.Uri

data class AlbumInfo(val id: Long, val title: String, val artUri: Uri?)
data class ArtistInfo(val id: Long, val name: String, val albums: List<AlbumInfo>)
data class TrackInfo(
    val id: Long,
    val title: String,
    val trackNumber: Int,
    val durationMs: Long,
    val discNumber: Int = 1,
    val albumId: Long = -1L,
    val albumTitle: String = "Unknown Album",
    val artist: String = "Unknown Artist",
    val isAccurateRipVerified: Boolean = false,
    val dataPath: String? = null
)
