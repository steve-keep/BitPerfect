package com.bitperfect.app.output

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.bitperfect.app.player.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

open class OutputRepository(
    private val context: Context,
    private val playerRepository: PlayerRepository,
    private val scope: CoroutineScope
) {

    private val _availableDevices = MutableStateFlow<List<OutputDevice>>(
        listOf(OutputDevice.ThisPhone)
    )
    open val availableDevices: StateFlow<List<OutputDevice>> = _availableDevices.asStateFlow()

    private val _activeDevice = MutableStateFlow<OutputDevice>(OutputDevice.ThisPhone)
    open val activeDevice: StateFlow<OutputDevice> = _activeDevice.asStateFlow()

    private var activeController: OutputController = LocalOutputController(playerRepository)

    // --- Playback delegation ---
    // AppViewModel calls these instead of PlayerRepository directly.

    fun play() { scope.launch { activeController.play() } }
    fun pause() { scope.launch { activeController.pause() } }
    fun togglePlayPause(isPlaying: Boolean) {
        if (isPlaying) pause() else play()
    }
    fun seekTo(positionMs: Long) { scope.launch { activeController.seekTo(positionMs) } }

    // --- Device switching ---

    /**
     * Switch active output to [target]. Captures current position from the active
     * controller before tearing it down, then hands off queue + position to the new one.
     *
     * @param currentMediaIds  The current play queue (from AppViewModel state).
     * @param currentIndex     The currently playing index in that queue.
     */
    fun switchTo(target: OutputDevice, currentMediaIds: List<String>, currentIndex: Int) {
        scope.launch {
            val positionMs = activeController.getPositionMs()
            activeController.release()

            val newController: OutputController = when (target) {
                is OutputDevice.ThisPhone ->
                    LocalOutputController(playerRepository)
                is OutputDevice.Bluetooth ->
                    BluetoothOutputController(context, playerRepository, target)
                is OutputDevice.Upnp ->
                    throw UnsupportedOperationException("UPnP controller not yet implemented")
            }

            newController.takeOver(currentMediaIds, currentIndex, positionMs)
            activeController = newController
            _activeDevice.value = target
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
            _availableDevices.value = devices
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
