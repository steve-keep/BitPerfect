package com.bitperfect.app.ui

import android.app.Application
import com.bitperfect.app.output.OutputRepository
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.core.output.OutputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun fakeOutputRepository(application: Application, playerRepository: PlayerRepository): OutputRepository {
    return object : OutputRepository(application, playerRepository, CoroutineScope(Dispatchers.Unconfined)) {
        val _activeDevice = MutableStateFlow<OutputDevice>(OutputDevice.ThisPhone)
        override val activeDevice: StateFlow<OutputDevice> = _activeDevice.asStateFlow()
        override val availableDevices: StateFlow<List<OutputDevice>> = MutableStateFlow(listOf<OutputDevice>()).asStateFlow()
        override val wiimPositionMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val wiimCurrentTrackIndex: StateFlow<Int> = MutableStateFlow(-1).asStateFlow()
        override val wiimCurrentTitle: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override val wiimCurrentArtist: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override val wiimCurrentAlbum: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

        override suspend fun takeOverAndPlay(tracks: List<com.bitperfect.app.library.TrackInfo>, startIndex: Int) {
            playerRepository.playTrack(tracks, startIndex)
        }

        override suspend fun play() { playerRepository.play() }
        override suspend fun pause() { playerRepository.pause() }
        override suspend fun togglePlayPause() {
            playerRepository.togglePlayPause()
        }
        override suspend fun seekTo(positionMs: Long) { playerRepository.seekTo(positionMs) }
        override suspend fun getPositionMs(): Long = playerRepository.positionMs.value
        override suspend fun skipNext() {}
        override suspend fun skipPrev() {}
    }
}
