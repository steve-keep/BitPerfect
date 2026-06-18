

package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.bitperfect.core.output.TrackInfo
import com.bitperfect.app.player.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothA2dp
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.BitPerfectApplication

open class OutputRepository(
    private val context: Context,
    private val playerRepository: PlayerRepository,
    private val scope: CoroutineScope
) {

    private val _availableDevices = MutableStateFlow<List<OutputDevice>>(
        listOf(OutputDevice.ThisPhone)
    )
    open val availableDevices: StateFlow<List<OutputDevice>> = _availableDevices.asStateFlow()

    var userSelectedDevice: OutputDevice? = null

    private val _activeDevice = MutableStateFlow<OutputDevice>(OutputDevice.ThisPhone)
    open val activeDevice: StateFlow<OutputDevice> = _activeDevice.asStateFlow()







    open val isPlaying: StateFlow<Boolean> = playerRepository.isPlaying
        .stateIn(scope, SharingStarted.Eagerly, false)

    open val isDiscovering: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private val switchMutex = kotlinx.coroutines.sync.Mutex()
        private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    refreshDevices()
                }
            }
        }
    }

    init {
        refreshDevices()
        context.registerReceiver(
            bluetoothReceiver,
            IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        )



        scope.launch {
            val registry = (context.applicationContext as BitPerfectApplication).outputPluginRegistry
            registry.availableDevices.collect { pluginDevices ->
                val current = _availableDevices.value
            .filter { it is OutputDevice.ThisPhone || it is OutputDevice.Bluetooth }
                    .toMutableList()
                current.addAll(pluginDevices)
                _availableDevices.value = current

                val active = _activeDevice.value
                if (active is OutputDevice.UsbDac && pluginDevices.none { it is OutputDevice.UsbDac }) {
                    Log.w("OutputRepository", "USB DAC disconnected while active — falling back to ThisPhone")
                    switchTo(OutputDevice.ThisPhone, emptyList(), 0)
                }
            }
        }

            }

    // Add a cleanup method to stop discovery when app closes
    fun release() {
                try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
    }



    // --- Playback delegation ---
    // AppViewModel calls these instead of PlayerRepository directly.

    open suspend fun takeOverAndPlay(tracks: List<TrackInfo>, startIndex: Int) {
        playerRepository.playTrack(tracks, startIndex)
    }

    open suspend fun play() = playerRepository.play()
    open suspend fun pause() = playerRepository.pause()
    open suspend fun togglePlayPause() {
        playerRepository.togglePlayPause()
    }
    open suspend fun seekTo(positionMs: Long) = playerRepository.seekTo(positionMs)
    open suspend fun getPositionMs(): Long = playerRepository.positionMs.value

    open suspend fun skipNext() = playerRepository.skipNext()
    open suspend fun skipPrev() = playerRepository.skipPrev()

    open suspend fun appendToQueue(track: TrackInfo) {
        playerRepository.addToQueue(track)
    }

    open suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) {
        playerRepository.addAlbumToQueue(tracks)
    }

    open suspend fun insertNextInQueue(track: TrackInfo) {
        playerRepository.playNext(track)
    }

    open suspend fun insertAlbumNextInQueue(tracks: List<TrackInfo>) {
        playerRepository.playAlbumNext(tracks)
    }

    open suspend fun reorderQueue(fromIndex: Int, toIndex: Int) {
        playerRepository.moveMediaItem(fromIndex, toIndex)
    }

    open suspend fun removeFromQueue(index: Int) {
        playerRepository.removeMediaItem(index)
    }

    open suspend fun setVolume(volume: Int) {
        // Stubbed for Phase 3a
    }

    // --- Device switching ---

    /**
     * Switch active output to [target]. Captures current position from the active
     * controller before tearing it down, then hands off queue + position to the new one.
     *
     * @param currentTracks    The current play queue (from AppViewModel state).
     * @param currentIndex     The currently playing index in that queue.
     */
    fun switchTo(target: OutputDevice, currentTracks: List<TrackInfo>, currentIndex: Int) {
        scope.launch(Dispatchers.Main) {
            switchMutex.withLock {
                _activeDevice.value = target

                when (target) {
                    is OutputDevice.ThisPhone,
                    is OutputDevice.UsbDac,
                    is OutputDevice.Bluetooth -> {
                        val positionMs = playerRepository.positionMs.value
                        playerRepository.playTrack(currentTracks, currentIndex)
                        if (positionMs > 0) playerRepository.seekTo(positionMs)
                    }
                    is OutputDevice.Upnp -> {
                        // Handled by PlaybackService via WiimOutputPlugin
                    }
                }
            }
        }
    }

    // --- Discovery ---

    /**
     * Scans for available output devices and updates [availableDevices].
     * Call this when the output sheet is opened.
     * Always includes ThisPhone; appends connected A2DP Bluetooth devices.
     */
    fun refreshDevices() {
        scope.launch {
            val devices = mutableListOf<OutputDevice>(OutputDevice.ThisPhone)
            val btDevices = fetchConnectedA2dpDevices()
            devices.addAll(btDevices)
            // Add plugin devices
            val registry = (context.applicationContext as BitPerfectApplication).outputPluginRegistry
            devices.addAll(registry.availableDevices.value)

            _availableDevices.value = devices

            if (btDevices.isNotEmpty() && _activeDevice.value is OutputDevice.ThisPhone && userSelectedDevice == null) {
                // Auto switch to first connected Bluetooth device if current is ThisPhone and user hasn't overridden it
                switchMutex.withLock {
                    _activeDevice.value = btDevices.first()
                }
            }
        }
    }

    private suspend fun fetchConnectedA2dpDevices(): List<OutputDevice.Bluetooth> =
        suspendCancellableCoroutine { cont ->
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            try {
                val profileProxyResult = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            // Suppress permissions check warning because we require the permission in the manifest
                            // and will only invoke this if granted.
                            @Suppress("MissingPermission")
                            val connectedDevices = proxy.connectedDevices
                            val result = connectedDevices.map { bt ->
                                @Suppress("MissingPermission")
                                OutputDevice.Bluetooth(
                                    address = bt.address,
                                    name = bt.name ?: "Bluetooth Device",
                                    batteryPercent = null // expand later via BluetoothDevice extras
                                )
                            }
                            adapter.closeProfileProxy(profile, proxy)
                            if (cont.isActive) cont.resume(result)
                        } catch (e: Exception) {
                            adapter.closeProfileProxy(profile, proxy)
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }, BluetoothProfile.A2DP)

                if (!profileProxyResult) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
}
