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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.bitperfect.app.MainActivity
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.core.utils.SettingsManager
import com.bitperfect.app.BitPerfectApplication
import com.bitperfect.app.output.OutputRepository
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.app.output.WiimCastPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var wiimCastPlayer: WiimCastPlayer? = null
    private var isUsingUsbAudioSink = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val libraryRepository: LibraryRepository by lazy { LibraryRepository(this) }
    private val settingsManager: SettingsManager by lazy { SettingsManager(this) }
    private val outputRepository: OutputRepository
        get() = (application as BitPerfectApplication).outputRepository

    @androidx.media3.common.util.UnstableApi
    private fun buildExoPlayer(useUsbAudioSink: Boolean = false): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs    = */ 15_000,
                /* maxBufferMs    = */ 50_000,
                /* bufferForPlayback      = */ 1_500,
                /* bufferForPlaybackAfterRebuffer = */ 3_000
            )
            .build()

        val renderersFactory = if (useUsbAudioSink) {
            UsbAudioRenderersFactory(this)
        } else {
            DefaultRenderersFactory(this)
        }

        isUsingUsbAudioSink = useUsbAudioSink

        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build()
    }

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = buildExoPlayer()
        player = exoPlayer

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, BrowseCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        serviceScope.launch {
            outputRepository.activeDevice.collect { device ->
                handleDeviceSwitch(device)
            }
        }
    }

    private fun handleDeviceSwitch(target: OutputDevice) {
        val session = mediaLibrarySession ?: return

        when (target) {
            is OutputDevice.Upnp -> {
                // Pause local playback so audio doesn't come from both sources
                player?.pause()
                // Release any existing WiimCastPlayer before creating a new one
                wiimCastPlayer?.release()

                val newCastPlayer = WiimCastPlayer(this, target)
                wiimCastPlayer = newCastPlayer
                session.player = newCastPlayer
            }
            is OutputDevice.UsbDac -> {
                wiimCastPlayer?.release()
                wiimCastPlayer = null

                // Capture state from current player before releasing
                val currentPosition = player?.currentPosition ?: 0L
                val wasPlaying = player?.isPlaying ?: false
                val currentMediaItem = player?.currentMediaItem

                player?.release()
                val newPlayer = buildExoPlayer(useUsbAudioSink = true)
                player = newPlayer

                // Restore state
                currentMediaItem?.let { newPlayer.setMediaItem(it, currentPosition) }
                newPlayer.prepare()
                if (wasPlaying) newPlayer.play()

                session.player = newPlayer
            }
            else -> {
                // Switching back to local — rebuild with DefaultRenderersFactory
                // if we were previously on UsbDac
                wiimCastPlayer?.release()
                wiimCastPlayer = null

                if (target is OutputDevice.ThisPhone || target is OutputDevice.Bluetooth) {
                    if (isUsingUsbAudioSink) {
                        val currentPosition = player?.currentPosition ?: 0L
                        val wasPlaying = player?.isPlaying ?: false
                        val currentMediaItem = player?.currentMediaItem

                        player?.release()
                        val newPlayer = buildExoPlayer(useUsbAudioSink = false)
                        player = newPlayer

                        // Restore state
                        currentMediaItem?.let { newPlayer.setMediaItem(it, currentPosition) }
                        newPlayer.prepare()
                        if (wasPlaying) newPlayer.play()
                    }
                }

                session.player = player ?: return
            }
        }
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
                    val folderExtras = Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    }

                    val recentlyAddedItem = MediaItem.Builder()
                        .setMediaId("recent_added")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("New")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setExtras(folderExtras)
                                .build()
                        )
                        .build()

                    val recentAlbumsItem = MediaItem.Builder()
                        .setMediaId("recent_albums")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Recent")
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setExtras(folderExtras)
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
                                .setExtras(folderExtras)
                                .build()
                        )
                        .build()

                    listOf(recentlyAddedItem, recentAlbumsItem, allAlbumsItem)
                }
                "recent_added" -> {
                    val recentAddedAlbums = libraryRepository.getLatestRippedAlbums(outputFolderUri)
                    recentAddedAlbums.map { (artist, album) ->
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
                                    .setIsBrowsable(true)
                                    .setIsPlayable(true)
                                    .setExtras(albumExtras)
                                    .build()
                            )
                            .build()
                    }
                }
                "recent_albums" -> {
                    val recent = libraryRepository.getRecentlyPlayedAlbums(outputFolderUri, 50)
                    val artistCounts = mutableMapOf<String, Int>()
                    for ((artistInfo, _) in recent) {
                        artistCounts[artistInfo.name] = artistCounts.getOrDefault(artistInfo.name, 0) + 1
                    }

                    val processedArtists = mutableSetOf<String>()
                    val recentlyPlayedItems = mutableListOf<MediaItem>()

                    for ((artistInfo, albumInfo) in recent) {
                        if (recentlyPlayedItems.size >= 10) break

                        val count = artistCounts.getOrDefault(artistInfo.name, 0)
                        if (count > 1) {
                            if (processedArtists.contains(artistInfo.name)) {
                                continue
                            }
                            val thumbnailUrl = libraryRepository.getArtistThumbnailUrl(artistInfo.name, outputFolderUri)
                            if (thumbnailUrl != null) {
                                val artistExtras = Bundle().apply {
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                }
                                val artistItem = MediaItem.Builder()
                                    .setMediaId("artist_${artistInfo.name}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(artistInfo.name)
                                            .setArtworkUri(android.net.Uri.parse(thumbnailUrl))
                                            .setIsBrowsable(true)
                                            .setIsPlayable(false)
                                            .setExtras(artistExtras)
                                            .build()
                                    )
                                    .build()
                                recentlyPlayedItems.add(artistItem)
                                processedArtists.add(artistInfo.name)
                            } else {
                                val albumExtras = Bundle().apply {
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                }
                                recentlyPlayedItems.add(
                                    MediaItem.Builder()
                                        .setMediaId("album_${albumInfo.id}")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(albumInfo.title)
                                                .setSubtitle(artistInfo.name)
                                                .setArtist(artistInfo.name)
                                                .setArtworkUri(albumInfo.artUri)
                                                .setIsBrowsable(true)
                                                .setIsPlayable(true)
                                                .setExtras(albumExtras)
                                                .build()
                                        )
                                        .build()
                                )
                            }
                        } else {
                            val albumExtras = Bundle().apply {
                                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                            }
                            recentlyPlayedItems.add(
                                MediaItem.Builder()
                                    .setMediaId("album_${albumInfo.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(albumInfo.title)
                                            .setSubtitle(artistInfo.name)
                                            .setArtist(artistInfo.name)
                                            .setArtworkUri(albumInfo.artUri)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setExtras(albumExtras)
                                            .build()
                                    )
                                    .build()
                            )
                        }
                    }
                    recentlyPlayedItems
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
                                    .setIsBrowsable(true)
                                    .setIsPlayable(true)
                                    .setExtras(albumExtras)
                                    .build()
                            )
                            .build()
                    }
                }
                else -> {
                    if (parentId.startsWith("artist_")) {
                        val artistName = parentId.removePrefix("artist_")
                        val artists = libraryRepository.getLibrary(outputFolderUri)
                        val artistInfo = artists.find { it.name == artistName }
                        if (artistInfo != null) {
                            artistInfo.albums.map { album ->
                                val albumExtras = Bundle().apply {
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                                }
                                MediaItem.Builder()
                                    .setMediaId("album_${album.id}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(album.title)
                                            .setSubtitle(artistInfo.name)
                                            .setArtist(artistInfo.name)
                                            .setArtworkUri(album.artUri)
                                            .setIsBrowsable(true)
                                            .setIsPlayable(true)
                                            .setExtras(albumExtras)
                                            .build()
                                    )
                                    .build()
                            }
                        } else {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
            }

            val resultParams = if (parentId == "root" || parentId == "recent_added" || parentId == "recent_albums" || parentId == "all_albums" || parentId.startsWith("artist_")) {
                val styleExtras = Bundle().apply {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                }
                LibraryParams.Builder().setExtras(styleExtras).build()
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
                    val tracks = libraryRepository.getTracksForAlbum(albumId, settingsManager.outputFolderUri)

                    for (track in tracks) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
                        val albumArtUri = if (track.albumId != -1L) ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), track.albumId) else null

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
                                    .build()
                            )
                            .build()
                        resolvedItems.add(resolvedItem)
                    }
                } else {
                    val trackId = mediaItem.mediaId.toLongOrNull() ?: continue

                    if (mediaItem.mediaMetadata.title != null) {
                        // Fast path: metadata already populated by PlayerRepository.
                        // Just reattach the URI — skip all database and SAF lookups.
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId
                        )
                        resolvedItems.add(mediaItem.buildUpon().setUri(uri).build())
                    } else {
                        // Slow path: bare mediaId from Android Auto browsing.
                        // Must look up metadata from the library.
                        val foundTrack = libraryRepository.getTrack(trackId, settingsManager.outputFolderUri)

                        if (foundTrack != null) {
                            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
                            val albumArtUri = if (foundTrack.albumId != -1L) ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), foundTrack.albumId) else null
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
                                        .build()
                                )
                                .build()
                            resolvedItems.add(resolvedItem)
                        }
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
        wiimCastPlayer?.release()
        wiimCastPlayer = null
        serviceScope.cancel()  // stops the activeDevice collection coroutine

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
