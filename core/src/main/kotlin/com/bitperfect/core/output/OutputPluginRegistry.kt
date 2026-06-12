package com.bitperfect.core.output

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central registry for [OutputPlugin] instances.
 *
 * Populated at app startup in [BitPerfectApplication]. [OutputRepository]
 * and [PlaybackService] interact with output hardware exclusively through
 * this registry — they do not import any plugin module directly.
 *
 * Thread-safety: all public methods are safe to call from any thread.
 */
@androidx.media3.common.util.UnstableApi
class OutputPluginRegistry {

    private val plugins = mutableMapOf<String, OutputPlugin>()

    private val _availableDevices = MutableStateFlow<List<OutputDevice>>(emptyList())

    /**
     * All devices currently advertised by registered plugins, combined.
     * [OutputRepository] collects this flow and merges it into its own
     * [availableDevices] alongside the always-present ThisPhone device.
     */
    val availableDevices: StateFlow<List<OutputDevice>> = _availableDevices.asStateFlow()

    /**
     * Register a plugin and call [OutputPlugin.attach] so it can start
     * discovery and begin contributing to [availableDevices].
     *
     * Registering a plugin with a [OutputPlugin.deviceType] that is
     * already registered replaces the previous registration and logs
     * a warning.
     */
    fun register(plugin: OutputPlugin) {
        val existing = plugins.put(plugin.deviceType, plugin)
        if (existing != null) {
            Log.w(TAG, "OutputPlugin for deviceType '${plugin.deviceType}' replaced. " +
                "Previous plugin was ${existing::class.simpleName}.")
        }
        plugin.attach(this)
    }

    /**
     * Called by a plugin during or after [OutputPlugin.attach] to publish
     * newly discovered devices. Replaces the previous device list for that
     * plugin's [deviceType].
     *
     * Thread-safe: may be called from any coroutine or thread.
     */
    @Synchronized
    fun updateDevices(deviceType: String, devices: List<OutputDevice>) {
        val current = _availableDevices.value.toMutableList()
        current.removeAll { deviceTypeOf(it) == deviceType }
        current.addAll(devices)
        _availableDevices.value = current
    }

    /**
     * Returns the [OutputPlugin] registered for [device], or null if no
     * plugin handles this device type.
     */
    fun pluginFor(device: OutputDevice): OutputPlugin? {
        return plugins[deviceTypeOf(device)]
    }

    /**
     * Release all registered plugins. Call from [Application.onTerminate]
     * or equivalent teardown.
     */
    fun releaseAll() {
        plugins.values.forEach { it.release() }
        plugins.clear()
    }

    /**
     * Maps an [OutputDevice] instance to its plugin [deviceType] string.
     * This mapping must stay in sync with the [deviceType] each plugin
     * declares.
     */
    private fun deviceTypeOf(device: OutputDevice): String = when (device) {
        is OutputDevice.UsbDac    -> "usb_dac"
        is OutputDevice.Upnp      -> "upnp_wiim"
        is OutputDevice.Bluetooth -> "bluetooth"
        is OutputDevice.ThisPhone -> "this_phone"
    }

    companion object {
        private const val TAG = "OutputPluginRegistry"
    }
}
