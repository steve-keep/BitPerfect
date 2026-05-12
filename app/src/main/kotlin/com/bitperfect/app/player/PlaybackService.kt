package com.bitperfect.app.player

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.bitperfect.app.MainActivity
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.core.utils.SettingsManager
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {
    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null

    private val libraryRepository: LibraryRepository by lazy { LibraryRepository(this) }
    private val settingsManager: SettingsManager by lazy { SettingsManager(this) }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player = exoPlayer

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.extras?.getLong("albumId", -1L)?.let { albumId ->
                    if (albumId != -1L) {
                        settingsManager.addRecentlyPlayedAlbum(albumId)
                    }
                }
            }
        })

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, BrowseCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    private inner class BrowseCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootExtras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("BitPerfect")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setExtras(rootExtras)
                        .build()
                )
                .build()

            val resultParams = LibraryParams.Builder()
                .setExtras(rootExtras)
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, resultParams))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val outputFolderUri = settingsManager.outputFolderUri

            val items = when (parentId) {
                "root" -> {
                    val recentItem = MediaItem.Builder()
                        .setMediaId("recent_albums")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Recently Played")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()

                    val allAlbumsItem = MediaItem.Builder()
                        .setMediaId("all_albums")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("All Albums")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .build()
                        )
                        .build()

                    listOf(recentItem, allAlbumsItem)
                }
                "recent_albums" -> {
                    val recentIds = settingsManager.recentlyPlayedAlbumIds
                    val artists = libraryRepository.getLibrary(outputFolderUri)
                    val allAlbums = artists.flatMap { artist ->
                        artist.albums.map { album ->
                            artist to album
                        }
                    }
                    val recentAlbums = allAlbums.filter { it.second.id in recentIds }

                    val sortedAlbums = recentAlbums.sortedBy { (_, album) -> recentIds.indexOf(album.id) }

                    sortedAlbums.map { (artist, album) ->
                        val albumExtras = Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        }
                        MediaItem.Builder()
                            .setMediaId("album_${album.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(album.title)
                                    .setSubtitle(artist.name)
                                    .setArtist(artist.name)
                                    .setArtworkUri(album.artUri)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setExtras(albumExtras)
                                    .build()
                            )
                            .build()
                    }
                }
                "all_albums" -> {
                    val artists = libraryRepository.getLibrary(outputFolderUri)
                    val allAlbums = artists.flatMap { artist ->
                        artist.albums.map { album ->
                            artist to album
                        }
                    }

                    val sortedAlbums = allAlbums.sortedWith(
                        compareBy<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>> { it.first.name }
                            .thenBy { it.second.title }
                    )

                    sortedAlbums.map { (artist, album) ->
                        val albumExtras = Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        }
                        MediaItem.Builder()
                            .setMediaId("album_${album.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(album.title)
                                    .setSubtitle(artist.name)
                                    .setArtist(artist.name)
                                    .setArtworkUri(album.artUri)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setExtras(albumExtras)
                                    .build()
                            )
                            .build()
                    }
                }
                else -> emptyList()
            }

            val resultParams = if (parentId == "root" || parentId == "all_albums" || parentId == "recent_albums") {
                val gridExtras = Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                }
                LibraryParams.Builder().setExtras(gridExtras).build()
            } else {
                params
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), resultParams)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolvedItems = mutableListOf<MediaItem>()

            for (mediaItem in mediaItems) {
                if (mediaItem.mediaId.startsWith("album_")) {
                    val albumId = mediaItem.mediaId.removePrefix("album_").toLongOrNull() ?: continue
                    val tracks = libraryRepository.getTracksForAlbum(albumId)

                    for (track in tracks) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
                        val albumArtUri = if (track.albumId != -1L) ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), track.albumId) else null

                        val extras = Bundle().apply {
                            putLong("albumId", track.albumId)
                        }
                        val resolvedItem = MediaItem.Builder()
                            .setMediaId("${track.id}")
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist)
                                    .setAlbumTitle(track.albumTitle)
                                    .setTrackNumber(track.trackNumber)
                                    .setArtworkUri(albumArtUri)
                                    .setExtras(extras)
                                    .build()
                            )
                            .build()
                        resolvedItems.add(resolvedItem)
                    }
                } else {
                    val trackId = mediaItem.mediaId.toLongOrNull() ?: continue
                    val foundTrack = libraryRepository.getTrack(trackId)

                    if (foundTrack != null) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
                        val albumArtUri = if (foundTrack.albumId != -1L) ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), foundTrack.albumId) else null
                        val extras = Bundle().apply {
                            putLong("albumId", foundTrack.albumId)
                        }
                        val resolvedItem = MediaItem.Builder()
                            .setMediaId(mediaItem.mediaId)
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(foundTrack.title)
                                    .setArtist(foundTrack.artist)
                                    .setAlbumTitle(foundTrack.albumTitle)
                                    .setTrackNumber(foundTrack.trackNumber)
                                    .setArtworkUri(albumArtUri)
                                    .setExtras(extras)
                                    .build()
                            )
                            .build()
                        resolvedItems.add(resolvedItem)
                    }
                }
            }
            return Futures.immediateFuture(resolvedItems)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession?.apply {
            release()
            mediaLibrarySession = null
        }
        player?.apply {
            release()
            player = null
        }
        super.onDestroy()
    }
}
