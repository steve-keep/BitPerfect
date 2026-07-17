package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.WiimDebugLogger

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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WiimCastPlayer(
    private val context: Context,
    private val targetDevice: OutputDevice.Upnp,
    initialPlaylist: List<MediaItem> = emptyList(),
    initialIndex: Int = 0,
    @get:androidx.annotation.VisibleForTesting
    internal val controller: WiimOutputController = WiimOutputController(context, targetDevice)
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var currentPlaylist: List<MediaItem> = initialPlaylist
    private var currentIndex: Int = initialIndex

    @androidx.annotation.VisibleForTesting
    internal var pendingPlayWhenReady: Boolean = false

    private var pendingTakeOverJob: Job? = null

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

        scope.launch { controller.durationMs.collect { invalidateState() } }
        scope.launch { controller.isMuted.collect { invalidateState() } }
        scope.launch { controller.currentTitle.collect { invalidateState() } }
        scope.launch { controller.currentArtist.collect { invalidateState() } }
        scope.launch { controller.currentAlbum.collect { invalidateState() } }
        scope.launch { controller.albumArtUrl.collect { invalidateState() } }
    }

    override fun getState(): State {
        WiimDebugLogger.log("getState: playlist.size=${currentPlaylist.size}, currentIndex=$currentIndex, pendingPlay=$pendingPlayWhenReady, controllerIsPlaying=${controller.isPlaying.value}")
        val playlist = currentPlaylist.mapIndexed { index, item ->
            val meta = item.mediaMetadata
            val durationUs = meta.extras
                ?.getLong("track_duration_ms", 0L)
                ?.takeIf { it > 0L }
                ?.let { it * 1_000L }
                ?: C.TIME_UNSET
            var finalDurationUs = durationUs
            if (index == currentIndex && controller.durationMs.value > 0L) {
                finalDurationUs = controller.durationMs.value * 1_000L
            }

            var finalMeta = meta
            if (index == currentIndex) {
                val b = finalMeta.buildUpon()
                controller.currentTitle.value?.let { b.setTitle(it) }
                controller.currentArtist.value?.let { b.setArtist(it) }
                controller.currentAlbum.value?.let { b.setAlbumTitle(it) }
                controller.albumArtUrl.value?.let { b.setArtworkUri(android.net.Uri.parse(it)) }
                finalMeta = b.build()
            }

            SimpleBasePlayer.MediaItemData.Builder(item.mediaId)
                .setMediaItem(item.buildUpon().setMediaMetadata(finalMeta).build())
                .setMediaMetadata(finalMeta)
                .setDurationUs(finalDurationUs)
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

            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SET_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SET_DEVICE_VOLUME,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE
                    )
                    .build()
            )
            .build()
    }

    fun activateWithHandoff(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean
    ) {
        currentPlaylist = mediaItems.toList()
        currentIndex = startIndex
        pendingPlayWhenReady = playWhenReady
        invalidateState()

        val tracks = mediaItems.mapNotNull { it.toTrackInfo() }
        scope.launch(Dispatchers.IO) {
            controller.takeOver(tracks, startIndex, startPositionMs, playWhenReady)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                pendingPlayWhenReady = false
                invalidateState()
            }
        }
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        WiimDebugLogger.log("handleSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex")
        currentPlaylist = mediaItems.toList()
        currentIndex = startIndex

        val tracks = mediaItems.mapNotNull { item -> item.toTrackInfo() }

        pendingTakeOverJob = scope.launch(Dispatchers.IO) {
            controller.takeOver(tracks as List<com.bitperfect.core.output.TrackInfo>, startIndex, startPositionMs, pendingPlayWhenReady)
        }

        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        WiimDebugLogger.log("handleSetPlayWhenReady: $playWhenReady")
        pendingPlayWhenReady = playWhenReady
        scope.launch {
            pendingTakeOverJob?.let { job ->
                try {
                    withTimeout(6000) { job.join() }
                } catch (e: TimeoutCancellationException) {
                    WiimDebugLogger.log("handleSetPlayWhenReady: takeOver job timed out after 6000ms, proceeding with play/pause anyway")
                }
                pendingTakeOverJob = null
            }
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
