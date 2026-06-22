package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.bitperfect.core.output.TrackInfo
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

    private var currentPlaylist: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0

    @androidx.annotation.VisibleForTesting
    internal var pendingPlayWhenReady: Boolean = false

    init {
        scope.launch {
            controller.isPlaying.collect { playing ->
                if (playing) pendingPlayWhenReady = false
                invalidateState()
            }
        }

        scope.launch {
            controller.positionMs.collect { posMs ->
                invalidateState()
            }
        }

        scope.launch {
            controller.volume.collect {
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val playlist = currentPlaylist.mapIndexed { index, item ->
            val meta = item.mediaMetadata
            val durationUs = meta.extras
                ?.getLong("track_duration_ms", 0L)
                ?.takeIf { it > 0L }
                ?.let { it * 1_000L }
                ?: C.TIME_UNSET
            SimpleBasePlayer.MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .setMediaMetadata(meta)
                .setDurationUs(durationUs)
                .build()
        }
        return State.Builder()
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) 0 else currentIndex)
            .setPlayWhenReady(
                pendingPlayWhenReady || controller.isPlaying.value,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setContentPositionMs { controller.positionMs.value }
            .setDeviceVolume(controller.volume.value)
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
            controller.takeOver(tracks as List<com.bitperfect.core.output.TrackInfo>, startIndex, startPositionMs)
        }

        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        pendingPlayWhenReady = playWhenReady
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

    private fun MediaItem.toTrackInfo(): TrackInfo? {
        val trackId = mediaId.toLongOrNull() ?: return null
        val extras: Bundle = mediaMetadata.extras ?: Bundle()
        return TrackInfo(
            id           = trackId,
            title        = mediaMetadata.title?.toString() ?: "",
            trackNumber  = mediaMetadata.trackNumber ?: 0,
            durationMs   = extras.getLong("track_duration_ms", 0L),
            albumId      = mediaId.toLongOrNull()?.let {
                               mediaMetadata.artworkUri?.lastPathSegment?.toLongOrNull() ?: -1L
                           } ?: -1L,
            albumTitle   = mediaMetadata.albumTitle?.toString() ?: "",
            artist       = mediaMetadata.artist?.toString() ?: "",
            filePath     = extras.getString("track_file_path"),
            dataPath     = extras.getString("track_data_path")
        )
    }
}
