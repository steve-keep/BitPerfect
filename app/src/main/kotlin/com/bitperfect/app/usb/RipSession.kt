package com.bitperfect.app.usb

import android.content.Context
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.models.LyricsFetchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.bitperfect.core.models.LyricsResult

class RipSession(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var ripManager: RipManager? = null
    private var stateCollectionJob: kotlinx.coroutines.Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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
        lyricsMap: Map<Int, LyricsFetchResult> = emptyMap(),
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
        val driveVendor = info?.vendor ?: ""
        val driveProduct = info?.model ?: ""

        val previousStates = if (_ripStates.value.isNotEmpty()) _ripStates.value else null

        val manager = RipManager(
            context = context,
            outputFolderUriString = outputFolderUriString,
            toc = toc,
            metadata = metadata,
            expectedChecksums = expectedChecksums,
            artworkBytes = artworkBytes,
            lyricsMap = lyricsMap,
            driveVendor = driveVendor,
            driveProduct = driveProduct,
            initialTracks = tracksToRip ?: toc.tracks.map { it.trackNumber },
            previousStates = previousStates
        )
        ripManager = manager
        _isRipping.value = true

        // Synchronously copy the initial states from RipManager so that it is instantly available
        _ripStates.value = manager.trackStates.value

        // Acquire wake lock synchronously BEFORE starting the service or USB I/O.
        // RipService.onCreate() runs asynchronously on the main thread, so we cannot
        // rely on its wake lock to be held before the IO coroutine begins.
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
        wakeLock?.release()
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "BitPerfect:RipWakeLock"
        ).also { it.acquire(WAKE_LOCK_TIMEOUT_MS) } // Timeout safety net
        com.bitperfect.core.utils.AppLogger.d("RipSession", "Wake lock acquired")

        val intent = android.content.Intent(context, RipService::class.java).apply {
            putExtra(RipService.EXTRA_ARTIST, metadata.artistName)
            putExtra(RipService.EXTRA_ALBUM, metadata.albumTitle)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)

        stateCollectionJob?.cancel()
        stateCollectionJob = scope.launch {
            manager.trackStates.collect { states ->
                _ripStates.value = states
            }
        }

        scope.launch {
            var unexpectedError = false
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    UsbReadSession.open().use { session ->
                        manager.startRipping(session)
                    }
                }
            } catch (e: Exception) {
                unexpectedError = true
                com.bitperfect.core.utils.AppLogger.e("RipSession", "Failed to open USB session", e)
            } finally {
                _isRipping.value = false
                wakeLock?.release()
                wakeLock = null
                com.bitperfect.core.utils.AppLogger.d("RipSession", "Wake lock released")
                // Do NOT call DeviceStateManager.rescan() here — it destroys the USB
                // transport and endpoints, preventing the polling loop from recovering.
                // If the error was due to USB suspension, the polling loop will detect
                // the connection state naturally and reconnect as needed.
                if (unexpectedError && manager.isCancelled == false) {
                    com.bitperfect.core.utils.AppLogger.w("RipSession", "Rip ended with unexpected error — USB connection preserved for polling loop recovery")
                }
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
        wakeLock?.release()
        wakeLock = null
        stateCollectionJob?.cancel()
        _ripStates.value = emptyMap()
    }

    fun clearResults() {
        if (_isRipping.value) return  // don't clear while ripping
        ripManager = null
        stateCollectionJob?.cancel()
        _ripStates.value = emptyMap()
    }

    companion object {
        private var instance: RipSession? = null
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L // 30-minute timeout safety net

        fun getInstance(context: Context): RipSession {
            return instance ?: RipSession(context.applicationContext).also { instance = it }
        }
    }
}
