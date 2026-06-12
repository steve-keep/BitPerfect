package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import androidx.car.app.connection.CarConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class SpeakerTypeProvider(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var outputRepository: OutputRepository? = null

    private val _speakerType = MutableStateFlow(SpeakerType.OTHER)
    val speakerType: StateFlow<SpeakerType> = _speakerType.asStateFlow()

    private var isAndroidAutoConnected = false

    private val carConnection = CarConnection(context)
    private val connectionObserver = Observer<Int> { connectionType ->
        isAndroidAutoConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
        updateSpeakerType()
    }

    init {
        // Observe CarConnection to determine Android Auto state

        // CarConnection.type returns LiveData<Int>. We must observe it on the main thread.
        scope.launch(Dispatchers.Main) {
            carConnection.type.observeForever(connectionObserver)
        }
    }

    fun setOutputRepository(repository: OutputRepository) {
        this.outputRepository = repository
        scope.launch {
            repository.activeDevice.collect { device ->
                updateSpeakerType()
            }
        }
    }

    private fun updateSpeakerType() {
        val device = outputRepository?.activeDevice?.value

        val newType = when {
            isAndroidAutoConnected -> SpeakerType.ANDROID_AUTO
            device is OutputDevice.Bluetooth -> SpeakerType.HEADPHONES
            else -> SpeakerType.OTHER
        }

        _speakerType.value = newType
    }

    fun getCurrentType(): SpeakerType {
        return _speakerType.value
    }

    fun clear() {
        scope.launch(Dispatchers.Main) {
            carConnection.type.removeObserver(connectionObserver)
        }
        scope.cancel()
    }
}
