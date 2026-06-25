package com.bitperfect.plugin.usbdac

import com.bitperfect.core.UsbDacDebugLogger
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bitperfect.core.output.PlayerProvider

@UnstableApi
class ExoPlayerProvider(
    private val exoPlayer: ExoPlayer,
    private val renderersFactory: UsbAudioRenderersFactory,
    private val mediaItems: List<MediaItem>,
    private val startIndex: Int,
    private val startPositionMs: Long,
    private val playWhenReady: Boolean
) : PlayerProvider {

    override val player: ExoPlayer = exoPlayer

    init {
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    fun setVolume(volume: Float) {
        renderersFactory.setVolume(volume)
        UsbDacDebugLogger.log("ExoPlayerProvider.setVolume: $volume")
    }

    override fun release() {
        exoPlayer.stop()
        exoPlayer.release()
    }
}
