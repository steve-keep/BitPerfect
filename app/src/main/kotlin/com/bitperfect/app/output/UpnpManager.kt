package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL

class UpnpManager(
    private val context: Context,
    fetchJson: ((String) -> JSONObject?)? = null
) {

    private val TAG = "UpnpManager"
    private val fetchJsonConfigured: (String) -> JSONObject? = fetchJson ?: { url -> fetchLinkPlayJson(url) }

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _devices = MutableStateFlow<List<OutputDevice.Upnp>>(emptyList())
    val devices: StateFlow<List<OutputDevice.Upnp>> = _devices.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_MX = 3
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val DISCOVERY_TIMEOUT_MS = 5_000L
        private const val SOCKET_TIMEOUT_MS = 5_000
        private val SSDP_M_SEARCH =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $SSDP_MX\r\n" +
            "ST: $SEARCH_TARGET\r\n" +
            "\r\n"
    }

    private fun fetchLinkPlayJson(url: String): JSONObject? {
        return try {
            val conn = openTrustAllConnection(url)
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject(body)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchLinkPlayJson $url: ${e.message}")
            null
        }
    }

    fun start() {
        Log.d(TAG, "start() called")
        if (_isDiscovering.value) {
            Log.d(TAG, "Already discovering, skipping")
            return
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _isDiscovering.value = true
        _devices.value = emptyList()

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("WiimDiscoveryLock")
        multicastLock?.setReferenceCounted(false)

        scope.launch {
            try {
                multicastLock?.acquire()
                Log.d(TAG, "MulticastLock held: ${multicastLock?.isHeld}")
                discoverDevices()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                _isDiscovering.value = false
                if (multicastLock?.isHeld == true) multicastLock?.release()
                Log.d(TAG, "Discovery complete, found ${_devices.value.size} device(s)")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called")
        scope.cancel()
        if (multicastLock?.isHeld == true) multicastLock?.release()
        _isDiscovering.value = false
    }

    private suspend fun discoverDevices() = withContext(Dispatchers.IO) {
        val foundIps = mutableSetOf<String>()
        val devices = mutableListOf<OutputDevice.Upnp>()

        try {
            val socket = MulticastSocket()
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.timeToLive = 4

            val group = InetAddress.getByName(SSDP_ADDRESS)

            // Bind to the WiFi interface so multicast traffic stays on the local network
            try {
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wifiIpInt = wifiManager.connectionInfo.ipAddress
                val wifiAddress = java.net.InetAddress.getByAddress(
                    byteArrayOf(
                        (wifiIpInt and 0xff).toByte(),
                        (wifiIpInt shr 8 and 0xff).toByte(),
                        (wifiIpInt shr 16 and 0xff).toByte(),
                        (wifiIpInt shr 24 and 0xff).toByte()
                    )
                )
                val wifiInterface = java.net.NetworkInterface.getByInetAddress(wifiAddress)
                if (wifiInterface != null) {
                    socket.joinGroup(java.net.InetSocketAddress(group, SSDP_PORT), wifiInterface)
                    Log.d(TAG, "Joined multicast group on interface: ${wifiInterface.name}")
                } else {
                    Log.w(TAG, "Could not find WiFi network interface, proceeding without joinGroup")
                }
            } catch (e: Exception) {
                Log.w(TAG, "joinGroup failed, proceeding anyway: ${e.message}")
            }
            val requestBytes = SSDP_M_SEARCH.toByteArray(Charsets.UTF_8)
            val request = DatagramPacket(requestBytes, requestBytes.size, group, SSDP_PORT)

            Log.d(TAG, "Sending M-SEARCH for $SEARCH_TARGET")
            socket.send(request)

            val deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            val buffer = ByteArray(4096)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)

                    val text = String(response.data, 0, response.length, Charsets.UTF_8)
                    val ip = extractLocation(text) ?: response.address.hostAddress ?: continue

                    if (ip in foundIps) continue
                    foundIps.add(ip)

                    Log.d(TAG, "SSDP response from $ip, probing LinkPlay API...")

                    val device = probeWiimDevice(ip)
                    if (device != null) {
                        devices.add(device)
                        _devices.value = devices.toList()
                        Log.d(TAG, "WiiM device confirmed: ${device.friendlyName} @ $ip")
                    } else {
                        Log.d(TAG, "Device at $ip did not respond to LinkPlay API, skipping")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // No more responses in this window — normal, exit loop
                    break
                }
            }

            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "SSDP socket error", e)
        }
    }

    /**
     * Extract the IP address from the LOCATION header in an SSDP response.
     * e.g. "LOCATION: http://192.168.1.42:49152/description.xml" -> "192.168.1.42"
     */
    private fun extractLocation(response: String): String? {
        val line = response.lines()
            .firstOrNull { it.trim().startsWith("LOCATION", ignoreCase = true) }
            ?: return null
        // Line is like "LOCATION: http://192.168.1.42:49152/description.xml"
        // Strip the header name and colon, keeping the full URL including scheme
        val url = line.substringAfter("LOCATION:").trim()
            .let { if (it.startsWith("LOCATION:", ignoreCase = true)) it.substringAfter(":").trim() else it }
        return try {
            java.net.URL(url).host.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Confirm a discovered IP is a WiiM/LinkPlay device by hitting its HTTP API.
     * Returns an OutputDevice.Upnp built from the API response, or null if not a WiiM.
     */
    private fun probeWiimDevice(ip: String): OutputDevice.Upnp? {
        val baseUrls = listOf(
            "https://$ip:443",
            "https://$ip:4443",
            "http://$ip",
        )

        for (baseUrl in baseUrls) {
            val playerJson = fetchJsonConfigured("$baseUrl/httpapi.asp?command=getPlayerStatusEx")
            val statusJson = fetchJsonConfigured("$baseUrl/httpapi.asp?command=getStatusEx")

            val device = mergeDeviceJson(playerJson, statusJson, ip, baseUrl)
            if (device != null) return device
        }
        return null
    }

    internal fun mergeDeviceJson(
        playerJson: JSONObject?,
        statusJson: JSONObject?,
        ip: String,
        baseUrl: String
    ): OutputDevice.Upnp? {
        // Need at least one to succeed to confirm this is a LinkPlay device
        if (playerJson == null && statusJson == null) return null

        val avTransportControlUrl = "http://$ip:49152/upnp/control/rendertransport1"
        val renderingControlUrl = "http://$ip:49152/upnp/control/rendercontrol1"

        // Merge: getStatusEx wins for identity fields, getPlayerStatusEx for everything else
        val friendlyName = statusJson?.optString("DeviceName")?.takeIf { it.isNotEmpty() }
            ?: statusJson?.optString("device_name")?.takeIf { it.isNotEmpty() }
            ?: playerJson?.optString("DeviceName")?.takeIf { it.isNotEmpty() }
            ?: playerJson?.optString("device_name")?.takeIf { it.isNotEmpty() }
            ?: "WiiM @ $ip"

        val modelName = statusJson?.optString("hardware")?.takeIf { it.isNotEmpty() }
            ?: statusJson?.optString("project")?.takeIf { it.isNotEmpty() }

        val udn = statusJson?.optString("uuid")?.takeIf { it.isNotEmpty() }
            ?: playerJson?.optString("uuid")?.takeIf { it.isNotEmpty() }
            ?: ip

        Log.d(TAG, "WiiM confirmed at $baseUrl — name=$friendlyName model=$modelName")

        return OutputDevice.Upnp(
            udn = udn,
            friendlyName = friendlyName,
            manufacturer = "WiiM",
            modelName = modelName,
            deviceDescriptionUrl = "$baseUrl/httpapi.asp?command=getStatusEx",
            avTransportControlUrl = avTransportControlUrl,
            renderingControlUrl = renderingControlUrl,
            ipAddress = ip
        )
    }

}
