package com.bitperfect.app.usb

import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.models.LyricsFetchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RipParameters(
    val outputFolderUriString: String,
    val toc: DiscToc,
    val metadata: DiscMetadata,
    val expectedChecksums: Map<Int, List<AccurateRipTrackMetadata>>,
    val artworkBytes: ByteArray?,
    val lyricsMap: Map<Int, LyricsFetchResult>,
    val tracksToRip: List<Int>?
)

class RipRepository private constructor() {

    private val _ripStates = MutableStateFlow<Map<Int, TrackRipState>>(emptyMap())
    val ripStates: StateFlow<Map<Int, TrackRipState>> = _ripStates.asStateFlow()

    private val _isRipping = MutableStateFlow(false)
    val isRipping: StateFlow<Boolean> = _isRipping.asStateFlow()

    var pendingRipParameters: RipParameters? = null

    // For queueing new tracks if already ripping
    private val _queueTrackEvent = MutableStateFlow<Int?>(null)
    val queueTrackEvent: StateFlow<Int?> = _queueTrackEvent.asStateFlow()

    // For cancelling rip
    private val _cancelEvent = MutableStateFlow<Boolean?>(null)
    val cancelEvent: StateFlow<Boolean?> = _cancelEvent.asStateFlow()

    fun updateRipStates(states: Map<Int, TrackRipState>) {
        _ripStates.value = states
    }

    fun setIsRipping(isRipping: Boolean) {
        _isRipping.value = isRipping
    }

    fun queueTrack(trackNumber: Int) {
        _queueTrackEvent.value = trackNumber
    }

    fun clearQueueTrackEvent() {
        _queueTrackEvent.value = null
    }

    fun cancelRip(deleteFiles: Boolean = false) {
        _cancelEvent.value = deleteFiles
    }

    fun clearCancelEvent() {
        _cancelEvent.value = null
    }

    fun clearResults() {
        if (_isRipping.value) return
        _ripStates.value = emptyMap()
    }

    companion object {
        @Volatile
        private var instance: RipRepository? = null

        fun getInstance(): RipRepository {
            return instance ?: synchronized(this) {
                instance ?: RipRepository().also { instance = it }
            }
        }
    }
}
