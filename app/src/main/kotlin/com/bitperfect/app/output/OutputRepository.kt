

package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.bitperfect.app.library.TrackInfo
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
import com.bitperfect.app.usb.UsbDacState

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

    private val _wiimIsPlaying = MutableStateFlow(false)
    private val _wiimPositionMs = MutableStateFlow(0L)
    open val wiimPositionMs: StateFlow<Long> = _wiimPositionMs.asStateFlow()

    private val _wiimVolume = MutableStateFlow(50)
    open val wiimVolume: StateFlow<Int> = _wiimVolume.asStateFlow()

    private val _wiimCurrentTrackIndex = MutableStateFlow(-1)
    open val wiimCurrentTrackIndex: StateFlow<Int> = _wiimCurrentTrackIndex.asStateFlow()

    private val _wiimCurrentTitle = MutableStateFlow<String?>(null)
    open val wiimCurrentTitle: StateFlow<String?> = _wiimCurrentTitle.asStateFlow()

    private val _wiimCurrentArtist = MutableStateFlow<String?>(null)
    open val wiimCurrentArtist: StateFlow<String?> = _wiimCurrentArtist.asStateFlow()

    private val _wiimCurrentAlbum = MutableStateFlow<String?>(null)
    open val wiimCurrentAlbum: StateFlow<String?> = _wiimCurrentAlbum.asStateFlow()

    open val isPlaying: StateFlow<Boolean> = combine(
        _activeDevice,
        _wiimIsPlaying,
        playerRepository.isPlaying
    ) { device, wiimPlaying, localPlaying ->
        if (device is OutputDevice.Upnp) wiimPlaying else localPlaying
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private val upnpManager = UpnpManager(context)
    open val isDiscovering: StateFlow<Boolean> = upnpManager.isDiscovering

    private val switchMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var activeController: OutputController = LocalOutputController(context, playerRepository)

    private var wiimCollectionJob: kotlinx.coroutines.Job? = null

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
            upnpManager.devices.collect { upnpDevices ->
                Log.d("OutputRepository", "UPnP devices from manager: ${upnpDevices.map { it.friendlyName }}")
                val current = _availableDevices.value.filter { it !is OutputDevice.Upnp }.toMutableList()
                current.addAll(upnpDevices)
                Log.d("OutputRepository", "Combined output devices: ${current.map { it.displayName }}")
                _availableDevices.value = current
            }
        }

        scope.launch {
            DeviceStateManager.dacState.collect { dacState ->
                val current = _availableDevices.value.toMutableList()
                current.removeAll { it is OutputDevice.UsbDac }
                if (dacState is UsbDacState.Connected) {
                    current.add(OutputDevice.UsbDac(dacState.device, dacState.protocol, dacState.productName))
                }
                _availableDevices.value = current

                if (dacState is UsbDacState.Absent && _activeDevice.value is OutputDevice.UsbDac) {
                    Log.w("OutputRepository", "USB DAC disconnected while active — falling back to ThisPhone")
                    switchTo(OutputDevice.ThisPhone, emptyList(), 0)
                }
            }
        }

        upnpManager.start()
    }

    // Add a cleanup method to stop discovery when app closes
    fun release() {
        upnpManager.stop()
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
    }

    fun optimisticallyFlipWiimPlaying() {
        if (_activeDevice.value is OutputDevice.Upnp) {
            _wiimIsPlaying.value = !_wiimIsPlaying.value
        }
    }

    // --- Playback delegation ---
    // AppViewModel calls these instead of PlayerRepository directly.

    open suspend fun takeOverAndPlay(tracks: List<TrackInfo>, startIndex: Int) {
        activeController.takeOver(tracks, startIndex, 0L)
    }

    open suspend fun play() = activeController.play()
    open suspend fun pause() = activeController.pause()
    open suspend fun togglePlayPause() {
        activeController.togglePlayPause()
    }
    open suspend fun seekTo(positionMs: Long) = activeController.seekTo(positionMs)
    open suspend fun getPositionMs(): Long = activeController.getPositionMs()

    open suspend fun skipNext() = activeController.skipNext()
    open suspend fun skipPrev() = activeController.skipPrev()

    open suspend fun appendToQueue(track: TrackInfo) {
        activeController.appendToQueue(track)
    }

    open suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) {
        activeController.appendAlbumToQueue(tracks)
    }

    open suspend fun insertNextInQueue(track: TrackInfo) {
        activeController.insertNextInQueue(track)
    }

    open suspend fun insertAlbumNextInQueue(tracks: List<TrackInfo>) {
        activeController.insertAlbumNextInQueue(tracks)
    }

    open suspend fun reorderQueue(fromIndex: Int, toIndex: Int) {
        activeController.reorderQueue(fromIndex, toIndex)
    }

    open suspend fun removeFromQueue(index: Int) {
        activeController.removeFromQueue(index)
    }

    open suspend fun setVolume(volume: Int) {
        val controller = activeController
        if (controller is WiimOutputController) {
            controller.setVolume(volume)
            _wiimVolume.value = volume.coerceIn(0, 100)
        }
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
                val positionMs = activeController.getPositionMs()

                wiimCollectionJob?.cancel()
                _wiimIsPlaying.value = false
                _wiimPositionMs.value = 0L
                _wiimVolume.value = 50
                _wiimCurrentTrackIndex.value = -1
                _wiimCurrentTitle.value = null
                _wiimCurrentArtist.value = null
                _wiimCurrentAlbum.value = null

                val isLocalToLocal = (activeController is LocalOutputController || activeController is BluetoothOutputController) &&
                                     (target is OutputDevice.ThisPhone || target is OutputDevice.Bluetooth)

                if (!isLocalToLocal) {
                    activeController.release()
                } else if (activeController is LocalOutputController) {
                    (activeController as LocalOutputController).clearCommunicationDeviceOnly()
                }

                val newController: OutputController = when (target) {
                    is OutputDevice.ThisPhone ->
                        LocalOutputController(context, playerRepository)
                    is OutputDevice.Bluetooth ->
                        BluetoothOutputController(context, playerRepository, target)
                    is OutputDevice.Upnp -> {
                        val controller = WiimOutputController(context, target)
                        wiimCollectionJob = scope.launch {
                            launch { controller.isPlaying.collect { _wiimIsPlaying.value = it } }
                            launch { controller.positionMs.collect { _wiimPositionMs.value = it } }
                            launch { controller.volume.collect { _wiimVolume.value = it } }
                            launch { controller.currentTrackIndex.collect { _wiimCurrentTrackIndex.value = it } }
                            launch { controller.currentTitle.collect { _wiimCurrentTitle.value = it } }
                            launch { controller.currentArtist.collect { _wiimCurrentArtist.value = it } }
                            launch { controller.currentAlbum.collect { _wiimCurrentAlbum.value = it } }
                        }
                        controller
                    }
                    is OutputDevice.UsbDac ->
                        LocalOutputController(context, playerRepository)
                }

                if (!isLocalToLocal) {
                    newController.takeOver(currentTracks, currentIndex, positionMs)
                } else if (newController is LocalOutputController) {
                    newController.applyCommunicationDeviceOnly()
                }

                activeController = newController
                _activeDevice.value = target
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

            // Keep existing UPnP devices
            val existingUpnp = _availableDevices.value.filterIsInstance<OutputDevice.Upnp>()
            devices.addAll(existingUpnp)

            // Keep existing UsbDac devices
            val existingUsbDac = _availableDevices.value.filterIsInstance<OutputDevice.UsbDac>()
            devices.addAll(existingUsbDac)

            _availableDevices.value = devices

            if (btDevices.isNotEmpty() && _activeDevice.value is OutputDevice.ThisPhone && userSelectedDevice == null) {
                // Auto switch to first connected Bluetooth device if current is ThisPhone and user hasn't overridden it
                // Don't take over if we're just initializing to avoid wiping state
                switchMutex.withLock {
                    val positionMs = activeController.getPositionMs()
                    if (activeController is LocalOutputController) {
                        (activeController as LocalOutputController).clearCommunicationDeviceOnly()
                    } else {
                        activeController.release()
                    }
                    val newController = BluetoothOutputController(context, playerRepository, btDevices.first())
                    // Wait, if it's just initialized, takeOver doesn't need to do anything if tracks are empty
                    // BUT we don't have tracks. Actually, just changing the controller is enough for future playback.
                    activeController = newController
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
