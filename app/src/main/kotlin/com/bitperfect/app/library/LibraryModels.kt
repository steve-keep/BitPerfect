package com.bitperfect.app.library

import android.net.Uri

data class AlbumInfo(val id: Long, val title: String, val artUri: Uri?)
data class ArtistInfo(val id: Long, val name: String, val albums: List<AlbumInfo>)

sealed interface RecentlyPlayedItem {
    data class AlbumItem(val album: AlbumInfo) : RecentlyPlayedItem
    data class ArtistGroupItem(val artistName: String, val thumbnailUrl: String) : RecentlyPlayedItem
}
typealias TrackInfo = com.bitperfect.core.output.TrackInfo

data class TopArtist(
    val artistName: String,
    val playCount: Int,
    val artUri: Uri? = null
)

data class TopSong(
    val trackTitle: String,
    val artistName: String,
    val playCount: Int,
    val albumId: Long = -1L,
    val albumTitle: String = ""
)

data class ListeningStats(
    val mostListenedArtist: TopArtist?,
    val totalTimeListenedMs: Long,
    val topSongsAllTime: List<TopSong>,
    val topSongsThisMonth: List<TopSong>,
    val topSongsThisYear: List<TopSong>
)
