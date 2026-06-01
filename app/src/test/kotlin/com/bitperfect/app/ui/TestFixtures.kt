package com.bitperfect.app.ui

import android.app.Application
import com.bitperfect.app.output.OutputRepository
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.app.output.OutputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun fakeOutputRepository(application: Application, playerRepository: PlayerRepository): OutputRepository {
    return object : OutputRepository(application, CoroutineScope(Dispatchers.Unconfined)) {
        val _activeDevice = MutableStateFlow<OutputDevice>(OutputDevice.ThisPhone)
        override val activeDevice: StateFlow<OutputDevice> = _activeDevice.asStateFlow()
        override val availableDevices: StateFlow<List<OutputDevice>> = MutableStateFlow(listOf<OutputDevice>()).asStateFlow()
    }
}
