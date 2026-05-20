package com.bitperfect.app.output

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.RemoteService
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class UpnpManager(private val context: Context) {

    private val TAG = "UpnpManager"

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _devices = MutableStateFlow<List<OutputDevice.Upnp>>(emptyList())
    val devices: StateFlow<List<OutputDevice.Upnp>> = _devices.asStateFlow()

    private var upnpService: UpnpService? = null

    private val discoveredDevices = ConcurrentHashMap<String, OutputDevice.Upnp>()

    private var multicastLock: WifiManager.MulticastLock? = null

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice?) {
            if (device == null) return
            // We use wildcard generics to handle the complex signatures
            val d = device as Device<*, *, *>
            Log.d(TAG, "RemoteDevice discovered: ${d.displayString}")
            // Check for MediaRenderer
            if (d.isMediaRenderer()) {
                Log.d(TAG, "Discovered renderer: ${d.details?.friendlyName} (${d.identity?.udn?.identifierString})")
                d.toOutputDevice()?.let {
                    discoveredDevices[it.udn] = it
                    updateState()
                }
            }
        }

        override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice?) {
            if (device == null) return
            val d = device as Device<*, *, *>
            Log.d(TAG, "RemoteDevice removed: ${d.displayString}")
            if (d.isMediaRenderer()) {
                val udn = d.identity?.udn?.identifierString
                if (udn != null) {
                    discoveredDevices.remove(udn)
                    updateState()
                    Log.d(TAG, "Removed renderer: ${d.details?.friendlyName} ($udn)")
                }
            }
        }

        override fun localDeviceAdded(registry: Registry?, device: LocalDevice?) {
            if (device == null) return
            val d = device as Device<*, *, *>
            if (d.isMediaRenderer()) {
                Log.d(TAG, "LocalDevice discovered: ${d.displayString}")
                d.toOutputDevice()?.let {
                    discoveredDevices[it.udn] = it
                    updateState()
                }
            }
        }

        override fun localDeviceRemoved(registry: Registry?, device: LocalDevice?) {
            if (device == null) return
            val d = device as Device<*, *, *>
            if (d.isMediaRenderer()) {
                val udn = d.identity?.udn?.identifierString
                if (udn != null) {
                    discoveredDevices.remove(udn)
                    updateState()
                    Log.d(TAG, "Removed local renderer: ${d.details?.friendlyName} ($udn)")
                }
            }
        }
    }


    private fun Device<*, *, *>.hasAvTransport(): Boolean {
        return try {
            this.findService(UDAServiceType("AVTransport", 1)) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun Device<*, *, *>.hasRenderingControl(): Boolean {
        return try {
            this.findService(UDAServiceType("RenderingControl", 1)) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun Device<*, *, *>.isMediaRenderer(): Boolean {
        return this.hasAvTransport() || this.hasRenderingControl()
    }

    fun start() {
        Log.d(TAG, "UPnP Manager start() called")
        if (upnpService != null) {
            Log.d(TAG, "UPnP Manager already started")
            return
        }
        Log.d(TAG, "Starting UPnP Manager")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _isDiscovering.value = true

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("WiimDiscoveryLock")
        multicastLock?.setReferenceCounted(false)
        Log.d(TAG, "Acquiring Multicast Lock")
        multicastLock?.acquire()

        scope.launch {
            try {
                upnpService = UpnpServiceImpl(AndroidUpnpServiceConfiguration())
                Log.d(TAG, "Attaching RegistryListener")
                upnpService?.registry?.addListener(registryListener)

                Log.d(TAG, "Triggering explicit UPnP search")
                kotlinx.coroutines.delay(1000)
                upnpService?.controlPoint?.search()
            } catch(e: Exception) {
                Log.e(TAG, "Error starting UPnP Service", e)
                _isDiscovering.value = false
            } catch (e: Error) {
                Log.e(TAG, "Critical error starting UPnP Service", e)
                _isDiscovering.value = false
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping UPnP Manager")

        try {
            upnpService?.registry?.removeListener(registryListener)
            upnpService?.shutdown()
        } catch(e: Exception) {
            Log.e(TAG, "Error stopping UPnP Service", e)
        } finally {
            upnpService = null

            if (multicastLock?.isHeld == true) {
                Log.d(TAG, "Releasing Multicast Lock")
                multicastLock?.release()
            }

            scope.cancel()
        }
    }

    private fun Device<*, *, *>.toOutputDevice(): OutputDevice.Upnp? {
        val identity = this.identity
        val udn = identity?.udn?.identifierString ?: return null

        val details = this.details
        val friendlyName = details?.friendlyName ?: "Unknown Device"
        val manufacturer = details?.manufacturerDetails?.manufacturer
        val modelName = details?.modelDetails?.modelName

        var ipAddress: String? = null
        var deviceDescriptionUrl = ""

        if (this is RemoteDevice) {
            val urlObj = this.identity?.descriptorURL
            if (urlObj != null) {
                ipAddress = urlObj.host
                deviceDescriptionUrl = urlObj.toString()
            }
        } else if (this is LocalDevice) {
            ipAddress = "127.0.0.1"
        }

        var avTransportControlUrl: String? = null
        var renderingControlUrl: String? = null

        try {
            val avTransportService = this.findService(UDAServiceType("AVTransport", 1))
            val renderingControlService = this.findService(UDAServiceType("RenderingControl", 1))

            Log.d(TAG, "Services for $friendlyName: AVTransport=${avTransportService != null}, RenderingControl=${renderingControlService != null}")

            if (avTransportService is RemoteService) {
                val controlUri = avTransportService.controlURI?.toString()
                if (!controlUri.isNullOrEmpty()) {
                    val urlObj = (this as? RemoteDevice)?.identity?.descriptorURL
                    if (urlObj != null) {
                        avTransportControlUrl = URL(urlObj, controlUri).toString()
                    }
                }
            }

            if (renderingControlService is RemoteService) {
                val controlUri = renderingControlService.controlURI?.toString()
                if (!controlUri.isNullOrEmpty()) {
                    val urlObj = (this as? RemoteDevice)?.identity?.descriptorURL
                    if (urlObj != null) {
                        renderingControlUrl = URL(urlObj, controlUri).toString()
                    }
                }
            }
        } catch (e: Exception) {
             Log.w(TAG, "Could not extract Service URIs", e)
        }

        return OutputDevice.Upnp(
            udn = udn,
            friendlyName = friendlyName,
            manufacturer = manufacturer,
            modelName = modelName,
            deviceDescriptionUrl = deviceDescriptionUrl,
            avTransportControlUrl = avTransportControlUrl,
            renderingControlUrl = renderingControlUrl,
            ipAddress = ipAddress
        )
    }

    private fun updateState() {
        _devices.value = discoveredDevices.values
            .distinctBy { it.udn }
            .sortedBy { it.friendlyName }
        Log.d(TAG, "Renderer count: ${_devices.value.size}")
        Log.d(TAG, "Publishing UPnP devices: ${_devices.value.map { it.friendlyName }}")
        _isDiscovering.value = false
    }
}
