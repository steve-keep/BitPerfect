package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.bitperfect.app.library.TrackInfo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WiimCastPlayer(
    private val context: Context,
    private val targetDevice: OutputDevice.Upnp
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val controller = WiimOutputController(context, targetDevice)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // Snapshot of the playlist handed to handleSetMediaItems; needed to rebuild
    // State.playlist so the session timeline stays non-empty.
    private var currentPlaylist: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0

    init {
        // Feed WiimOutputController's isPlaying into the session state
        scope.launch {
            controller.isPlaying.collect { playing ->
                invalidateState()
            }
        }

        // Feed position into the session state
        scope.launch {
            controller.positionMs.collect { posMs ->
                invalidateState()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // SimpleBasePlayer contract
    // ---------------------------------------------------------------------------

    override fun getState(): State {
        val playlist = currentPlaylist.mapIndexed { index, item ->
            SimpleBasePlayer.MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .build()
        }
        return State.Builder()
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) 0 else currentIndex)
            .setPlayWhenReady(
                controller.isPlaying.value,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setContentPositionMs { controller.positionMs.value }
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        currentPlaylist = mediaItems.toList()
        currentIndex = startIndex

        val tracks = mediaItems.mapNotNull { item -> item.toTrackInfo() }

        scope.launch(Dispatchers.IO) {
            controller.takeOver(tracks, startIndex, startPositionMs)
        }

        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        scope.launch {
            if (playWhenReady) controller.play() else controller.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        currentIndex = mediaItemIndex
        scope.launch { controller.seekTo(positionMs) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> {
        scope.launch { controller.setVolume(volume) }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.launch { controller.release() }
        return Futures.immediateVoidFuture()
    }

    // ---------------------------------------------------------------------------
    // MediaItem → TrackInfo reconstruction
    // ---------------------------------------------------------------------------

    private fun MediaItem.toTrackInfo(): TrackInfo? {
        val trackId = mediaId.toLongOrNull() ?: return null
        val extras: Bundle = mediaMetadata.extras ?: Bundle()
        return TrackInfo(
            id           = trackId,
            title        = mediaMetadata.title?.toString() ?: "",
            trackNumber  = mediaMetadata.trackNumber ?: 0,
            durationMs   = extras.getLong("track_duration_ms", 0L),
            albumId      = mediaId.toLongOrNull()?.let {
                               // albumId is not directly in MediaItem; derive from artwork URI
                               // content://media/external/audio/albumart/<albumId>
                               mediaMetadata.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L
                           } ?: -1L,
            albumTitle   = mediaMetadata.albumTitle?.toString() ?: "",
            artist       = mediaMetadata.artist?.toString() ?: "",
            filePath     = extras.getString("track_file_path"),
            dataPath     = extras.getString("track_data_path")
        )
    }
}
