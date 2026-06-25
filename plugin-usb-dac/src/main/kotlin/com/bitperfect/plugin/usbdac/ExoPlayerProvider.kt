package com.bitperfect.plugin.usbdac

import com.bitperfect.core.UsbDacDebugLogger
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bitperfect.core.output.PlayerProvider

@UnstableApi
class ExoPlayerProvider(
    private val exoPlayer: ExoPlayer,
    private val mediaItems: List<MediaItem>,
    private val startIndex: Int,
    private val startPositionMs: Long,
    private val playWhenReady: Boolean,
    startVolume: Float = 0.5f,
) : PlayerProvider {

    override val player: ExoPlayer = exoPlayer

    init {
        exoPlayer.volume = startVolume
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReady
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        UsbDacDebugLogger.log("ExoPlayerProvider.setVolume: setting $clamped (was ${exoPlayer.volume})")
        exoPlayer.volume = clamped
        UsbDacDebugLogger.log("ExoPlayerProvider.setVolume: confirmed exoPlayer.volume=${exoPlayer.volume}")
    }

    override fun release() {
        exoPlayer.stop()
        exoPlayer.release()
    }
}
