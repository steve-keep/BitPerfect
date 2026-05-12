package com.bitperfect.app.usb

import android.content.Context
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.AccurateRipTrackMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RipSession(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ripManager: RipManager? = null

    private val _ripStates = MutableStateFlow<Map<Int, TrackRipState>>(emptyMap())
    val ripStates: StateFlow<Map<Int, TrackRipState>> = _ripStates.asStateFlow()

    private val _isRipping = MutableStateFlow(false)
    val isRipping: StateFlow<Boolean> = _isRipping.asStateFlow()

    fun startRip(
        outputFolderUriString: String,
        toc: DiscToc,
        metadata: DiscMetadata,
        expectedChecksums: Map<Int, List<AccurateRipTrackMetadata>>,
        artworkBytes: ByteArray?,
        tracksToRip: List<Int>? = null
    ) {
        if (_isRipping.value) {
            val manager = ripManager
            if (manager != null && tracksToRip != null) {
                for (track in tracksToRip) {
                    manager.queueTrack(track)
                }
            }
            return
        }

        val info = DeviceStateManager.driveStatus.value.info
        val driveVendor = info?.vendorId ?: ""
        val driveProduct = info?.productId ?: ""

        val manager = RipManager(
            context = context,
            outputFolderUriString = outputFolderUriString,
            toc = toc,
            metadata = metadata,
            expectedChecksums = expectedChecksums,
            artworkBytes = artworkBytes,
            driveVendor = driveVendor,
            driveProduct = driveProduct,
            initialTracks = tracksToRip ?: toc.tracks.map { it.trackNumber }
        )
        ripManager = manager
        _isRipping.value = true

        // Synchronously copy the initial states from RipManager so that it is instantly available
        _ripStates.value = manager.trackStates.value

        val intent = android.content.Intent(context, RipService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)

        scope.launch {
            manager.trackStates.collect { states ->
                _ripStates.value = states
            }
        }

        scope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    UsbReadSession.open().use { session ->
                        manager.startRipping(session)
                    }
                }
            } catch (e: Exception) {
                com.bitperfect.core.utils.AppLogger.e("RipSession", "Failed to open USB session", e)
            } finally {
                _isRipping.value = false
            }
        }
    }

    fun cancel(deleteFiles: Boolean = false) {
        val manager = ripManager
        manager?.cancel()
        if (deleteFiles) {
            manager?.deleteRipFiles()
        }
        _isRipping.value = false
        _ripStates.value = emptyMap()
    }

    fun clearResults() {
        if (_isRipping.value) return  // don't clear while ripping
        ripManager = null
        _ripStates.value = emptyMap()
    }

    companion object {
        private var instance: RipSession? = null

        fun getInstance(context: Context): RipSession {
            return instance ?: RipSession(context.applicationContext).also { instance = it }
        }
    }
}
