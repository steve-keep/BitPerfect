package com.bitperfect.app.output

import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.player.PlayerRepository

import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build

class LocalOutputController(
    private val context: Context,
    private val playerRepository: PlayerRepository
) : OutputController {

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

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        playerRepository.playTrack(tracks, startIndex)
        if (startPositionMs > 0) {
            playerRepository.seekTo(startPositionMs)
        }
    }

    override suspend fun release() {
        clearCommunicationDeviceOnly()
        // Local controller is never fully released — just pause
        playerRepository.pause()
    }

    fun clearCommunicationDeviceOnly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    fun applyCommunicationDeviceOnly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }
}
