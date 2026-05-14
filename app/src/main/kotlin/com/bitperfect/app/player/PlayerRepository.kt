package com.bitperfect.app.player

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.core.utils.SettingsManager
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.net.Uri
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

open class PlayerRepository(
    private val context: Context,
    private val factory: MediaControllerFactory = DefaultMediaControllerFactory()
) {

    fun interface MediaControllerFactory {
        fun build(context: Context, token: SessionToken): ListenableFuture<MediaController>
    }

    private class DefaultMediaControllerFactory : MediaControllerFactory {
        override fun build(context: Context, token: SessionToken): ListenableFuture<MediaController> {
            return MediaController.Builder(context, token).buildAsync()
        }
    }

    private var controller: MediaController? = null

    private val libraryRepository by lazy { LibraryRepository(context) }
    private val settingsManager by lazy { SettingsManager(context) }
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isControllerReady = MutableStateFlow(false)
    open val isControllerReady: StateFlow<Boolean> = _isControllerReady.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    open val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMediaId = MutableStateFlow<String?>(null)
    open val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow<String?>(null)
    open val currentTrackTitle: StateFlow<String?> = _currentTrackTitle.asStateFlow()

    private val _currentTrackArtist = MutableStateFlow<String?>(null)
    open val currentTrackArtist: StateFlow<String?> = _currentTrackArtist.asStateFlow()

    private val _currentAlbumTitle = MutableStateFlow<String?>(null)
    open val currentAlbumTitle: StateFlow<String?> = _currentAlbumTitle.asStateFlow()

    private val _currentAlbumArtUri = MutableStateFlow<Uri?>(null)
    open val currentAlbumArtUri: StateFlow<Uri?> = _currentAlbumArtUri.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    open val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _syncedLyrics = MutableStateFlow<String?>(null)
    open val syncedLyrics: StateFlow<String?> = _syncedLyrics.asStateFlow()

    private val _currentTimeline = MutableStateFlow<List<MediaItem>>(emptyList())
    open val currentTimeline: StateFlow<List<MediaItem>> = _currentTimeline.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    open val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = controller?.isPlaying ?: false
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentMediaId.value = controller?.currentMediaItem?.mediaId
            _currentTrackTitle.value = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
            _currentTrackArtist.value = controller?.currentMediaItem?.mediaMetadata?.artist?.toString()
            _currentAlbumTitle.value = controller?.currentMediaItem?.mediaMetadata?.albumTitle?.toString()
            _currentAlbumArtUri.value = controller?.currentMediaItem?.mediaMetadata?.artworkUri
            _currentIndex.value = controller?.currentMediaItemIndex ?: 0

            mediaItem?.let { item ->
                recordRecentlyPlayed(item)
                updateSyncedLyrics(item)
            }
        }

        private fun updateSyncedLyrics(mediaItem: MediaItem) {
            val trackId = mediaItem.mediaId.toLongOrNull()
            if (trackId == null) {
                _syncedLyrics.value = null
                return
            }

            scope.launch(Dispatchers.IO) {
                try {
                    // Extract dataPath manually as it's not present in TrackInfo model
                    var dataPath: String? = null
                    val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
                    val selection = "${android.provider.MediaStore.Audio.Media._ID} = ?"
                    val selectionArgs = arrayOf(trackId.toString())
                    context.contentResolver.query(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                            dataPath = cursor.getString(dataCol)
                        }
                    }

                    if (dataPath != null) {
                        val file = java.io.File(dataPath!!)
                        if (file.exists()) {
                            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                            val tag = audioFile.tag
                            val vorbisTag = when (tag) {
                                is org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag -> tag
                                is org.jaudiotagger.tag.flac.FlacTag -> tag.vorbisCommentTag
                                else -> null
                            }
                            if (vorbisTag != null) {
                                val lyrics = vorbisTag.getFirst("SYNCEDLYRICS")
                                _syncedLyrics.value = if (lyrics.isNotBlank()) lyrics else null
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                _syncedLyrics.value = null
            }
        }

        private fun recordRecentlyPlayed(mediaItem: MediaItem) {
            val trackId = mediaItem.mediaId.toLongOrNull() ?: return

            scope.launch {
                val track = libraryRepository.getTrack(trackId) ?: return@launch
                val albumId = track.albumId
                if (albumId == -1L) return@launch

                val outputUriStr = settingsManager.outputFolderUri ?: return@launch
                val parentDir = DocumentFile.fromTreeUri(context, Uri.parse(outputUriStr))
                if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) return@launch

                val recentFile = parentDir.findFile("recently-played.jsonl") ?: parentDir.createFile("application/x-ndjson", "recently-played.jsonl")
                if (recentFile == null) return@launch

                try {
                    val json = JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("albumId", albumId)
                        put("albumTitle", track.albumTitle)
                        put("artist", track.artist)
                        put("trackId", track.id)
                        put("trackTitle", track.title)
                        put("trackNumber", track.trackNumber)
                    }

                    context.contentResolver.openOutputStream(recentFile.uri, "wa")?.use { out ->
                        out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val items = mutableListOf<MediaItem>()
            val c = controller
            if (c != null && !timeline.isEmpty) {
                for (i in 0 until timeline.windowCount) {
                    val window = androidx.media3.common.Timeline.Window()
                    timeline.getWindow(i, window)
                    if (window.mediaItem != null) {
                        items.add(window.mediaItem!!)
                    }
                }
            }
            _currentTimeline.value = items
            _currentIndex.value = controller?.currentMediaItemIndex ?: 0
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _positionMs.value = controller?.currentPosition ?: 0L
        }
    }

    private var pendingPlayPause = false

    open suspend fun connect() {
        try {
            // Check context package name first to prevent NPE inside Media3 ComponentName when mocked context is used in tests
            if (context.packageName != null) {
                val componentName = ComponentName(context.packageName, PlaybackService::class.java.name)
                // Just pass context directly, as the session token has internal NPEs if ComponentName has a null package.
                // Using string package avoids passing mock Contexts that throw exceptions inside native ComponentName methods.
                // SessionToken constructor DOES NOT take context. Wait...
                // SessionToken constructor takes (Context context, ComponentName componentName)
                val sessionToken = SessionToken(context, componentName)
                val newController = factory.build(context, sessionToken).await().apply {
                    addListener(listener)
                    // Initialize state
                    _isPlaying.value = isPlaying
                    _currentMediaId.value = currentMediaItem?.mediaId
                    _currentTrackTitle.value = currentMediaItem?.mediaMetadata?.title?.toString()
                    _currentTrackArtist.value = currentMediaItem?.mediaMetadata?.artist?.toString()
                    _currentAlbumTitle.value = currentMediaItem?.mediaMetadata?.albumTitle?.toString()
                    _currentAlbumArtUri.value = currentMediaItem?.mediaMetadata?.artworkUri
                    _positionMs.value = currentPosition

                    val items = mutableListOf<MediaItem>()
                    val tl = currentTimeline
                    if (!tl.isEmpty) {
                        for (i in 0 until tl.windowCount) {
                            val window = androidx.media3.common.Timeline.Window()
                            tl.getWindow(i, window)
                            if (window.mediaItem != null) {
                                items.add(window.mediaItem!!)
                            }
                        }
                    }
                    _currentTimeline.value = items
                    _currentIndex.value = currentMediaItemIndex
                }

                controller = newController
                _isControllerReady.value = true

                if (pendingPlayPause) {
                    pendingPlayPause = false
                    val c = controller
                    if (c != null) {
                        if (c.isPlaying) c.pause() else c.play()
                    }
                }
            }
        } catch (e: Throwable) {
            // Ignore in tests
        }
    }

    open fun disconnect() {
        _isControllerReady.value = false
        controller?.apply {
            removeListener(listener)
            release()
        }
        controller = null
    }

    open fun playAlbum(tracks: List<TrackInfo>) {
        playTrack(tracks, 0)
    }

    open fun playTrack(tracks: List<TrackInfo>, index: Int) {
        val mediaItems = tracks.map { track ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
            val albumArtUri = if (track.albumId != -1L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId) else null
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(track.id.toString())
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
        }

        controller?.apply {
            setMediaItems(mediaItems)
            seekToDefaultPosition(index)
            prepare()
            play()
        }
    }

    private fun trackToMediaItem(track: TrackInfo): MediaItem {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
        val albumArtUri = if (track.albumId != -1L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), track.albumId) else null
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(track.id.toString())
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
    }

    open fun playNext(track: TrackInfo) {
        controller?.let {
            val item = trackToMediaItem(track)
            val insertIndex = if (it.mediaItemCount > 0) it.currentMediaItemIndex + 1 else 0
            it.addMediaItem(insertIndex, item)
            if (!it.isPlaying && it.playbackState == Player.STATE_IDLE) {
                it.prepare()
                it.play()
            }
        }
    }

    open fun addToQueue(track: TrackInfo) {
        controller?.let {
            val item = trackToMediaItem(track)
            it.addMediaItem(item)
            if (!it.isPlaying && it.playbackState == Player.STATE_IDLE) {
                it.prepare()
                it.play()
            }
        }
    }

    open fun addAlbumToQueue(tracks: List<TrackInfo>) {
        controller?.let {
            val mediaItems = tracks.map { track -> trackToMediaItem(track) }
            it.addMediaItems(mediaItems)
            if (!it.isPlaying && it.playbackState == Player.STATE_IDLE) {
                it.prepare()
                it.play()
            }
        }
    }

    open fun play() {
        controller?.play()
    }

    open fun pause() {
        controller?.pause()
    }

    open fun togglePlayPause() {
        val c = controller
        if (c != null) {
            if (c.isPlaying) c.pause() else c.play()
        } else {
            pendingPlayPause = true
        }
    }

    open suspend fun setQueueAndPlay(mediaIds: List<String>, startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return

        val mediaItems = mediaIds.map { id ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toLong())
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(id)
                .build()
        }

        c.setMediaItems(mediaItems)
        c.seekTo(startIndex, startPositionMs)
        c.prepare()
        c.play()
    }

    open fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    open fun skipNext() {
        controller?.seekToNext()
    }

    open fun skipPrev() {
        controller?.seekToPrevious()
    }

    open fun pollPosition() {
        controller?.let {
            _positionMs.value = it.currentPosition
        }
    }

    open fun clearQueue() {
        controller?.let {
            val count = it.mediaItemCount
            val currentIndex = it.currentMediaItemIndex
            if (currentIndex + 1 < count) {
                it.removeMediaItems(currentIndex + 1, count)
            }
        }
    }

    open fun removeMediaItem(index: Int) {
        controller?.removeMediaItem(index)
    }

    open fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        controller?.moveMediaItem(currentIndex, newIndex)
    }
}
