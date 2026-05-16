package com.bitperfect.app.output

import com.bitperfect.app.player.PlayerRepository

class LocalOutputController(
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

    override suspend fun seekTo(positionMs: Long) {
        playerRepository.seekTo(positionMs)
    }

    override suspend fun takeOver(mediaIds: List<String>, startIndex: Int, startPositionMs: Long) {
        playerRepository.setQueueAndPlay(mediaIds, startIndex, startPositionMs)
    }

    override suspend fun release() {
        // Local controller is never fully released — just pause
        playerRepository.pause()
    }
}
