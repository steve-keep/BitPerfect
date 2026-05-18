package com.bitperfect.app.output

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.HttpURLConnection

class WiimDiscovery(private val context: Context) {

    suspend fun discover(): List<OutputDevice.Upnp> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("WiimDiscoveryLock")
        multicastLock.setReferenceCounted(true)
        multicastLock.acquire()

        val devices = mutableListOf<OutputDevice.Upnp>()
        val uniqueLocations = mutableSetOf<String>()

        try {
            val group = InetAddress.getByName("239.255.255.250")
            val port = 1900
            val searchMessage = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 3
                ST: urn:schemas-upnp-org:service:AVTransport:1

            """.trimIndent().replace("\n", "\r\n").toByteArray()

            val socket = MulticastSocket(port)
            socket.joinGroup(group)
            socket.soTimeout = 4000 // 4 seconds timeout loop

            val packet = DatagramPacket(searchMessage, searchMessage.size, group, port)
            // Send twice for reliability
            socket.send(packet)
            socket.send(packet)

            val receiveData = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 4000) {
                try {
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val locationLine = response.lines().find { it.startsWith("LOCATION:", ignoreCase = true) }

                    if (locationLine != null) {
                        val location = locationLine.substringAfter(":").trim()
                        if (uniqueLocations.add(location)) {
                            Log.d("WiimDiscovery", "Found location: $location")
                            val device = parseDeviceDescription(location)
                            if (device != null) {
                                devices.add(device)
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Ignore timeout and continue if within time limit, but break if we exceeded 4s total.
                    // Actually, socket.receive blocking means we might block up to 4s.
                    // So after a timeout we just loop back to check total time.
                } catch (e: Exception) {
                    Log.e("WiimDiscovery", "Error receiving SSDP", e)
                }
            }
            socket.leaveGroup(group)
            socket.close()
        } catch (e: Exception) {
            Log.e("WiimDiscovery", "Error during SSDP discovery", e)
        } finally {
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }

        devices
    }

    private fun parseDeviceDescription(locationUrlString: String): OutputDevice.Upnp? {
        try {
            val url = URL(locationUrlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val xml = inputStream.bufferedReader().use { it.readText() }

                val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>")
                if (friendlyName.isEmpty()) return null

                // Look for the AVTransport service to extract controlURL
                val avTransportIndex = xml.indexOf("urn:schemas-upnp-org:service:AVTransport:1")
                if (avTransportIndex == -1) return null

                // It should be within a <service> block that contains the AVTransport identifier
                val serviceBlockStart = xml.lastIndexOf("<service>", avTransportIndex)
                val serviceBlockEnd = xml.indexOf("</service>", avTransportIndex)
                if (serviceBlockStart == -1 || serviceBlockEnd == -1) return null

                val serviceBlock = xml.substring(serviceBlockStart, serviceBlockEnd)
                var controlUrl = serviceBlock.substringAfter("<controlURL>", "").substringBefore("</controlURL>").trim()

                if (controlUrl.isEmpty()) return null

                // Resolve relative controlUrl against base URL
                if (!controlUrl.startsWith("http")) {
                    val base = URL(url.protocol, url.host, url.port, "")
                    controlUrl = URL(base, if (controlUrl.startsWith("/")) controlUrl else "/$controlUrl").toString()
                }

                return OutputDevice.Upnp(locationUrlString, friendlyName, controlUrl)
            }
        } catch (e: Exception) {
            Log.e("WiimDiscovery", "Error parsing device desc: $locationUrlString", e)
        }
        return null
    }
}
