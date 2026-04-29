package com.bitperfect.app.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.core.utils.SettingsManager
import com.google.common.collect.ImmutableList
import android.content.ContentUris
import android.provider.MediaStore
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

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, BrowseCallback())
            .build()
    }

    private inner class BrowseCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("BitPerfect")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
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

            val items = when {
                parentId == "root" -> {
                    val artists = libraryRepository.getLibrary(outputFolderUri)
                    artists.map { artist ->
                        MediaItem.Builder()
                            .setMediaId("artist_${artist.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(artist.name)
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .build()
                            )
                            .build()
                    }
                }
                parentId.startsWith("artist_") -> {
                    val artistId = parentId.removePrefix("artist_").toLongOrNull()
                    val artists = libraryRepository.getLibrary(outputFolderUri)
                    val artist = artists.find { it.id == artistId }
                    artist?.albums?.map { album ->
                        MediaItem.Builder()
                            .setMediaId("album_${album.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(album.title)
                                    .setArtworkUri(album.artUri)
                                    .setIsBrowsable(true)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                    } ?: emptyList()
                }
                parentId.startsWith("album_") -> {
                    val albumId = parentId.removePrefix("album_").toLongOrNull() ?: -1L
                    val tracks = libraryRepository.getTracksForAlbum(albumId)
                    tracks.map { track ->
                        MediaItem.Builder()
                            .setMediaId("${track.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setTrackNumber(track.trackNumber)
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                    }
                }
                else -> emptyList()
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val resolvedItems = mutableListOf<MediaItem>()

            for (mediaItem in mediaItems) {
                val trackId = mediaItem.mediaId.toLongOrNull() ?: continue
                val foundTrack = libraryRepository.getTrack(trackId)

                if (foundTrack != null) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
                    val albumArtUri = if (foundTrack.albumId != -1L) ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), foundTrack.albumId) else null
                    val resolvedItem = MediaItem.Builder()
                        .setMediaId(mediaItem.mediaId)
                        .setUri(uri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(foundTrack.title)
                                .setTrackNumber(foundTrack.trackNumber)
                                .setArtworkUri(albumArtUri)
                                .build()
                        )
                        .build()
                    resolvedItems.add(resolvedItem)
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
