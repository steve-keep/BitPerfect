package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.WiimDebugLogger

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
import java.net.NetworkInterface

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
                    val body = withContext(Dispatchers.IO) { fetchLinkPlay("getPlayerStatus") }
                    if (body != null) {
                        val json = JSONObject(body)
                        val isNowPlaying = json.optString("status") == "play" || json.optString("play_status") == "play"
                        _isPlaying.value = isNowPlaying
                        if (isNowPlaying) {
                            val curpos = json.optLong("curpos", -1L)
                            if (curpos >= 0L) _positionMs.value = curpos
                        }
                        val vol = json.optInt("vol", -1)
                        if (vol in 0..100) _volume.value = vol
                        _currentTrackIndex.value = json.optInt("plicurr", -1)
                    }
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

    suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long, playWhenReady: Boolean) {
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
            // 1. Load the playlist and jump to startIndex
            val success = sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:$startIndex")

            if (success) {
                // 2. Seek within track
                if (startPositionMs > 0) {
                    val positionSec = startPositionMs / 1000
                    sendLinkPlayCommand("setPlayerCmd:seek:$positionSec")
                }

                if (playWhenReady) {
                    sendLinkPlayCommand("setPlayerCmd:resume")
                }
            }
        }

        startPolling()
    }

    suspend fun skipNext() {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:next")
        }
    }

    suspend fun skipPrev() {
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:prev")
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
            val positionSec = positionMs / 1000
            sendLinkPlayCommand("setPlayerCmd:seek:$positionSec")
        }
    }

    suspend fun getPositionMs(): Long = withContext(Dispatchers.IO) {
        val response = fetchLinkPlay("getPlayerStatus")
        if (response != null) {
            try {
                val json = JSONObject(response)
                val curpos = json.optLong("curpos", -1L)
                if (curpos >= 0L) {
                    return@withContext curpos
                }
            } catch (e: Exception) {
                Log.e("WiimOutputController", "Error parsing getPlayerStatus", e)
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
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        }
    }

    suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.appendTracks(tracks)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
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
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
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
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        }
    }

    suspend fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.moveTrack(fromIndex, toIndex)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        }
    }

    suspend fun removeFromQueue(index: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.removeTrack(index)

        val playlistUrl = "http://$ip:$port/playlist.m3u8"
        withContext(Dispatchers.IO) {
            sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
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


    private fun fetchLinkPlay(command: String): String? {
        val ip = target.ipAddress ?: return null
        val port = target.linkPlayPort
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), 3000)
            socket.soTimeout = 3000
            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val `in` = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            out.write("GET /httpapi.asp?command=$command HTTP/1.0\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n")
            out.flush()
            val lines = `in`.readLines()
            socket.close()
            // Skip HTTP headers, return body
            val blankIndex = lines.indexOfFirst { it.isBlank() }
            if (blankIndex >= 0) lines.drop(blankIndex + 1).joinToString("") else null
        } catch (e: Exception) {
            WiimDebugLogger.log("fetchLinkPlay FAILED: $command → ${e.message}")
            null
        }
    }

    private fun sendLinkPlayCommand(command: String): Boolean {
        val ip = target.ipAddress ?: return false
        val port = target.linkPlayPort
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), 3000)
            socket.soTimeout = 3000
            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val `in` = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            out.write("GET /httpapi.asp?command=$command HTTP/1.0\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n")
            out.flush()
            val firstLine = `in`.readLine() ?: ""
            socket.close()
            val ok = firstLine.isNotBlank()
            WiimDebugLogger.log("LinkPlay: $command → $firstLine")
            ok
        } catch (e: Exception) {
            WiimDebugLogger.log("LinkPlay FAILED: $command → ${e.message}")
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



}
