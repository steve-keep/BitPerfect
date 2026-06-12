package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import android.media.AudioManager
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.player.PlayerRepository

class BluetoothOutputController(
    private val context: Context,
    private val playerRepository: PlayerRepository,
    val device: OutputDevice.Bluetooth
) : OutputController {

    // TODO: use for explicit routing in phase 2
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun getPositionMs(): Long =
        playerRepository.positionMs.value

    override suspend fun play() {
        playerRepository.play()
    }

    override suspend fun pause() {
        playerRepository.pause()
    }

    override suspend fun togglePlayPause() {
        playerRepository.togglePlayPause()
    }

    override suspend fun seekTo(positionMs: Long) {
        playerRepository.seekTo(positionMs)
    }

    override suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long) {
        // Android routes A2DP automatically when the BT device is the active audio output.
        // No explicit routing call needed here for phase 1 — MediaSession + AVRCP handles it.
        playerRepository.playTrack(tracks, startIndex)
        if (startPositionMs > 0) {
            playerRepository.seekTo(startPositionMs)
        }
    }

    override suspend fun release() {
        playerRepository.pause()
    }
}
