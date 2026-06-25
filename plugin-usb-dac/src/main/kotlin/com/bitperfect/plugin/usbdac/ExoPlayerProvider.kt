package com.bitperfect.plugin.usbdac

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bitperfect.core.output.PlayerProvider

@UnstableApi
internal class ExoPlayerProvider(
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
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        exoPlayer.stop()
        exoPlayer.release()
    }
}
