package com.bitperfect.plugin.wiim

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.bitperfect.app.output.UpnpManager
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.OutputPlugin
import com.bitperfect.core.output.OutputPluginRegistry
import com.bitperfect.core.output.PlaybackHandoffState
import com.bitperfect.core.output.PlayerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class WiimOutputPlugin(
    private val appContext: Context,
    private val discoveryManagerFactory: (Context) -> UpnpManager =
        { ctx -> UpnpManager(ctx) }
) : OutputPlugin {

    override val deviceType: String = "upnp_wiim"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var discoveryManager: UpnpManager? = null

    override fun attach(registry: OutputPluginRegistry) {
        val manager = discoveryManagerFactory(appContext)
        discoveryManager = manager
        scope.launch {
            manager.devices.collect { devices ->
                registry.updateDevices(deviceType, devices)
            }
        }
        manager.start()
    }

    override fun createPlayerProvider(
        context: Context,
        device: OutputDevice,
        handoffState: PlaybackHandoffState,
    ): PlayerProvider {
        return WiimPlayerProvider(context, device as OutputDevice.Upnp, handoffState)
    }

    override fun release() {
        discoveryManager?.stop()
        discoveryManager = null
        scope.cancel()
    }
}
