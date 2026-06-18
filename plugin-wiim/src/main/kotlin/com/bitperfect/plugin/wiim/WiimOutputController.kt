package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bitperfect.core.output.TrackInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import com.bitperfect.plugin.wiim.FlacHttpServer
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.ContentUris
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class WiimOutputController(
    private val context: Context,
    private val target: OutputDevice.Upnp
) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var httpServer: com.bitperfect.plugin.wiim.FlacHttpServer? = null
    private var wifiIp: String? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _volume = MutableStateFlow(50)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _currentTrackIndex = MutableStateFlow(-1)
    val currentTrackIndex: StateFlow<Int> = _currentTrackIndex.asStateFlow()

    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle.asStateFlow()

    private val _currentArtist = MutableStateFlow<String?>(null)
    val currentArtist: StateFlow<String?> = _currentArtist.asStateFlow()

    private val _currentAlbum = MutableStateFlow<String?>(null)
    val currentAlbum: StateFlow<String?> = _currentAlbum.asStateFlow()

    private var pollingJob: Job? = null

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val url = URL("http://${target.ipAddress}/httpapi.asp?command=getPlayerStatusEx")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        // LinkPlay API typically uses "status", but check "play_status" just in case.
                        val isNowPlaying = json.optString("status") == "play" || json.optString("play_status") == "play"
                        _isPlaying.value = isNowPlaying

                        if (isNowPlaying) {
                            val curpos = json.optLong("curpos", -1L)
                            if (curpos >= 0L) {
                                _positionMs.value = curpos
                            }
                        }

                        val vol = json.optInt("vol", -1)
                        if (vol in 0..100) _volume.value = vol

                        _currentTrackIndex.value = json.optInt("plicurr", -1)
                        _currentTitle.value = decodeHexString(json.optString("Title").takeIf { it.isNotEmpty() })
                        _currentArtist.value = decodeHexString(json.optString("Artist").takeIf { it.isNotEmpty() })
                        _currentAlbum.value = decodeHexString(json.optString("Album").takeIf { it.isNotEmpty() })
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.d("WiimOutputController", "Polling error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPlaying.value = false
        _positionMs.value = 0L
    }

    private fun getWifiIpAddress(): String? {
        // Primary path: Use NetworkInterfaces (modern, robust approach)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.address.size == 4 && address.isSiteLocalAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WiimOutputController", "Error getting IP from NetworkInterfaces", e)
        }

        // Fallback to deprecated WifiManager approach
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e("WiimOutputController", "Error getting fallback IP", e)
        }
        return null
    }

    suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long) {
        val trackList = tracks
        val index = startIndex
        wifiIp = getWifiIpAddress()
        if (wifiIp == null) {
            Log.w("WiimOutputController", "Not on Wi-Fi or unable to get IP, ignoring takeOver")
            return
        }

        httpServer?.stop()
        httpServer = FlacHttpServer(context, trackList)
        httpServer?.serverIp = wifiIp ?: "127.0.0.1"
        httpServer?.start(5000, false)

        val port = httpServer?.listeningPort ?: -1
        if (port <= 0) {
            Log.e("WiimOutputController", "FlacHttpServer failed to bind to a port, aborting takeOver")
            return
        }

        val playlistUrl = "http://$wifiIp:$port/playlist.m3u8"

        withContext(Dispatchers.IO) {
            // 1. Load the playlist
            val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
            val success = sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")

            if (success) {
                // 2. Jump to startIndex (UPnP track numbers are 1-based)
                if (startIndex > 0) {
                    sendSoapAction(
                        "Seek",
                        """
                        <InstanceID>0</InstanceID>
                        <Unit>TRACK_NR</Unit>
                        <Target>${startIndex + 1}</Target>
                        """.trimIndent()
                    )
                }

                // 3. Seek within track
                if (startPositionMs > 0) {
                    val h = startPositionMs / 3600000
                    val m = (startPositionMs % 3600000) / 60000
                    val s = (startPositionMs % 60000) / 1000
                    val targetTime = String.format("%02d:%02d:%02d", h, m, s)
                    sendSoapAction(
                        "Seek",
                        """
                        <InstanceID>0</InstanceID>
                        <Unit>REL_TIME</Unit>
                        <Target>$targetTime</Target>
                        """.trimIndent()
                    )
                }
            }
        }

        startPolling()
    }

    suspend fun skipNext() {
        withContext(Dispatchers.IO) {
            sendSoapAction("Next", "<InstanceID>0</InstanceID>")
        }
    }

    suspend fun skipPrev() {
        withContext(Dispatchers.IO) {
            sendSoapAction("Previous", "<InstanceID>0</InstanceID>")
        }
    }

    suspend fun play() {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:resume")
        }
    }

    suspend fun pause() {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:pause")
        }
    }

    suspend fun togglePlayPause() {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:onepause")
        }
    }

    suspend fun seekTo(positionMs: Long) {
        withContext(Dispatchers.IO) {
            val h = positionMs / 3600000
            val m = (positionMs % 3600000) / 60000
            val s = (positionMs % 60000) / 1000
            val targetTime = String.format("%02d:%02d:%02d", h, m, s)
            sendSoapAction(
                "Seek",
                """
                <InstanceID>0</InstanceID>
                <Unit>REL_TIME</Unit>
                <Target>$targetTime</Target>
                """.trimIndent()
            )
        }
    }

    suspend fun getPositionMs(): Long = withContext(Dispatchers.IO) {
        val response = sendSoapActionWithResponse(
            "GetPositionInfo",
            """
            <InstanceID>0</InstanceID>
            """.trimIndent()
        )

        if (response != null) {
            try {
                val relTime = response.substringAfter("<RelTime>", "").substringBefore("</RelTime>")
                if (relTime.isNotEmpty()) {
                    val parts = relTime.split(":")
                    if (parts.size == 3) {
                        val h = parts[0].toLong()
                        val m = parts[1].toLong()
                        val s = parts[2].toLong()
                        return@withContext (h * 3600000) + (m * 60000) + (s * 1000)
                    }
                }
            } catch (e: Exception) {
                Log.e("WiimOutputController", "Error parsing GetPositionInfo", e)
            }
        }
        return@withContext 0L
    }

    suspend fun appendToQueue(track: TrackInfo) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.appendTrack(track)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.appendTracks(tracks)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun insertNextInQueue(track: TrackInfo) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return
        val currentIndex = _currentTrackIndex.value.coerceAtLeast(0)

        server.insertTrackAfterIndex(track, currentIndex)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun insertAlbumNextInQueue(tracks: List<TrackInfo>) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return
        val currentIndex = _currentTrackIndex.value.coerceAtLeast(0)

        server.insertTracksAfterIndex(tracks, currentIndex)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.moveTrack(fromIndex, toIndex)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun removeFromQueue(index: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.removeTrack(index)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        val encodedUrl = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$encodedUrl")
        }
    }

    suspend fun release() {
        stopPolling()
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:stop")
            httpServer?.stop()
        }
    }

    suspend fun setVolume(volume: Int) {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:vol:${volume.coerceIn(0, 100)}")
        }
    }

    private fun sendSoapAction(action: String, body: String): Boolean {
        return sendSoapActionWithResponse(action, body) != null
    }

    private fun sendLinkPlayCommand(command: String): Boolean {
        return try {
            val conn = URL("http://${target.ipAddress}/httpapi.asp?command=$command").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e("WiimOutputController", "LinkPlay command failed: $command", e)
            false
        }
    }

    private fun decodeHexString(hex: String?): String? {
        if (hex.isNullOrEmpty()) return null
        return try {
            val bytes = hex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
            String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    private fun sendSoapActionWithResponse(action: String, body: String): String? {
        if (target.avTransportControlUrl.isNullOrEmpty()) return null
        try {
            val url = URL(target.avTransportControlUrl!!)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")

            val envelope = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                    <s:Body>
                        <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                            $body
                        </u:$action>
                    </s:Body>
                </s:Envelope>
            """.trimIndent()

            connection.outputStream.use { os ->
                os.write(envelope.toByteArray())
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w("WiimOutputController", "SOAP action $action failed with code $responseCode")
                Log.w("WiimOutputController", connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            }
        } catch (e: Exception) {
            Log.e("WiimOutputController", "Exception sending SOAP action $action", e)
        }
        return null
    }


}
