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

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _albumArtUrl = MutableStateFlow<String?>(null)
    val albumArtUrl: StateFlow<String?> = _albumArtUrl.asStateFlow()

    private var subscriber: UpnpEventSubscriber? = null

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

                        // Only update from polling if events are not active or for fields events don't provide
                        val eventsActive = subscriber?.eventsActive == true

                        if (!eventsActive) {
                            _isPlaying.value = isNowPlaying
                            if (isNowPlaying) {
                                val curpos = json.optLong("curpos", -1L)
                                if (curpos >= 0L) _positionMs.value = curpos
                            }
                            val vol = json.optInt("vol", -1)
                            if (vol in 0..100) _volume.value = vol
                        }

                        _currentTrackIndex.value = json.optInt("plicurr", -1)
                    }
                } catch (e: Exception) {
                    Log.d("WiimOutputController", "Polling error: ${e.message}")
                }
                delay(if (subscriber?.eventsActive == true) 5000L else 1000L)
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

        subscriber?.let { scope.launch { it.stop() } }
        val s = UpnpEventSubscriber(target, wifiIp!!, scope) { stateChange ->
            stateChange.isPlaying?.let { _isPlaying.value = it }
            stateChange.positionMs?.let { _positionMs.value = it }
            stateChange.durationMs?.let { _durationMs.value = it }
            stateChange.volume?.let { _volume.value = it }
            stateChange.isMuted?.let { _isMuted.value = it }
            stateChange.title?.let { _currentTitle.value = it }
            stateChange.artist?.let { _currentArtist.value = it }
            stateChange.album?.let { _currentAlbum.value = it }
            stateChange.albumArtUrl?.let { _albumArtUrl.value = it }
        }
        s.start()
        subscriber = s


        httpServer?.stop()
        httpServer = FlacHttpServer(context, trackList)
        httpServer?.serverIp = wifiIp ?: "127.0.0.1"
        httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        val port = httpServer?.listeningPort ?: -1
        if (port <= 0) {
            Log.e("WiimOutputController", "FlacHttpServer failed to bind to a port, aborting takeOver")
            return
        }

        withContext(Dispatchers.IO) { Thread.sleep(200) }
        WiimDebugLogger.log("FlacHttpServer started on port $port, serverIp=$wifiIp")

        withContext(Dispatchers.IO) {
            val listName = tracks.firstOrNull()?.albumTitle?.take(15) ?: "BitPerfect"
            val queueXml = buildQueueXml(listName, tracks, wifiIp!!, port)

            WiimDebugLogger.log("QueueContext → ${queueXml.take(500)}")

            sendSoapToQueue(
                action = "CreateQueue",
                body = """
                    <u:CreateQueue xmlns:u="urn:schemas-wiimu-com:service:PlayQueue:1">
                        <QueueContext>$queueXml</QueueContext>
                    </u:CreateQueue>
                """.trimIndent()
            )

            sendSoapToQueue(
                action = "PlayQueueWithIndex",
                body = """
                    <u:PlayQueueWithIndex xmlns:u="urn:schemas-wiimu-com:service:PlayQueue:1">
                        <QueueName>$listName</QueueName>
                        <Index>$startIndex</Index>
                    </u:PlayQueueWithIndex>
                """.trimIndent()
            )

            if (playWhenReady) {
                val startTime = System.currentTimeMillis()
                WiimDebugLogger.log("takeOver: starting polling for play status...")
                var isPlaying = false
                for (i in 0 until 20) {
                    val body = fetchLinkPlay("getPlayerStatus")
                    if (body != null) {
                        try {
                            val json = JSONObject(body)
                            if (json.optString("status") == "play" || json.optString("play_status") == "play") {
                                isPlaying = true
                                break
                            }
                        } catch (e: Exception) {}
                    }
                    Thread.sleep(300)
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (isPlaying) {
                    WiimDebugLogger.log("takeOver: detected play status after ${elapsed}ms")
                } else {
                    WiimDebugLogger.log("takeOver: timed out after 6s waiting for WiiM to report playing status, sending seek anyway")
                }
            }

            if (startPositionMs > 0) {
                val positionSec = startPositionMs / 1000
                WiimDebugLogger.log("takeOver: sending seek to $positionSec seconds")
                // TEMP DIAGNOSTIC: seek disabled to test whether setPlayerCmd:seek breaks PlayQueueWithIndex sessions — see [ticket/issue ref if any]
                WiimDebugLogger.log("takeOver: [DIAGNOSTIC] skipping seek to $positionSec, letting queue play from start")
                // sendLinkPlayCommand("setPlayerCmd:seek:$positionSec")
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

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.appendTracks(tracks)

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun insertNextInQueue(track: TrackInfo) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return
        val currentIndex = _currentTrackIndex.value.coerceAtLeast(0)

        server.insertTrackAfterIndex(track, currentIndex)

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun insertAlbumNextInQueue(tracks: List<TrackInfo>) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return
        val currentIndex = _currentTrackIndex.value.coerceAtLeast(0)

        server.insertTracksAfterIndex(tracks, currentIndex)

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.moveTrack(fromIndex, toIndex)

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun removeFromQueue(index: Int) {
        val server = httpServer ?: return
        val ip = wifiIp ?: return
        val port = server.listeningPort
        if (port <= 0) return

        server.removeTrack(index)

        // val playlistUrl = "http://$ip:$port/playlist.m3u8"
        // withContext(Dispatchers.IO) {
        //     sendLinkPlayCommand("setPlayerCmd:playlist:$playlistUrl:0")
        // }
        WiimDebugLogger.log("TODO: Queue mutation not yet migrated to PlayQueue SOAP")
    }

    suspend fun release() {
        stopPolling()
        subscriber?.stop()
        subscriber = null
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
            `in`.readLines() // drain until WiiM closes connection
            socket.close()
            WiimDebugLogger.log("LinkPlay: $command → OK")
            true
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




    private fun sendSoapToQueue(action: String, body: String): String? {
        val ip = target.ipAddress ?: return null
        val soapAction = "urn:schemas-wiimu-com:service:PlayQueue:1#$action"
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>${body.trim()}</s:Body>
</s:Envelope>"""
        return try {
            WiimDebugLogger.log("SOAP $action endpoint → /upnp/control/PlayQueue1")
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, 49152), 3000)
            socket.soTimeout = 5000
            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val `in` = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val bodyBytes = envelope.toByteArray(Charsets.UTF_8)
            out.write("POST /upnp/control/PlayQueue1 HTTP/1.0\r\n")
            out.write("Host: $ip:49152\r\n")
            out.write("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            out.write("SOAPACTION: \"$soapAction\"\r\n")
            out.write("Content-Length: ${bodyBytes.size}\r\n")
            out.write("Connection: close\r\n\r\n")
            out.write(envelope)
            out.flush()
            val response = `in`.readLines().joinToString("\n")
            socket.close()
            WiimDebugLogger.log("SOAP $action body → ${envelope.take(300)}")
            WiimDebugLogger.log("SOAP $action → ${response.take(600)}")
            response
        } catch (e: Exception) {
            WiimDebugLogger.log("SOAP $action FAILED → ${e.message}")
            null
        }
    }

    private fun buildQueueXml(
        listName: String,
        tracks: List<TrackInfo>,
        wifiIp: String,
        port: Int
    ): String {
        val sb = StringBuilder()
        sb.append("&lt;?xml version=&quot;1.0&quot;?&gt;")
        sb.append("&lt;PlayList&gt;")
        sb.append("&lt;ListName&gt;${listName.escapeXml()}&lt;/ListName&gt;")
        sb.append("&lt;ListInfo&gt;")
        sb.append("&lt;Radio&gt;0&lt;/Radio&gt;")
        sb.append("&lt;SourceName&gt;PC_RemoteLocal&lt;/SourceName&gt;")
        sb.append("&lt;PicUrl&gt;&lt;/PicUrl&gt;")
        sb.append("&lt;TrackNumber&gt;${tracks.size}&lt;/TrackNumber&gt;")
        sb.append("&lt;SearchUrl&gt;&lt;/SearchUrl&gt;")
        sb.append("&lt;Quality&gt;0&lt;/Quality&gt;")
        sb.append("&lt;/ListInfo&gt;")
        sb.append("&lt;Tracks&gt;")
        tracks.forEachIndexed { i, track ->
            val trackUrl = "http://$wifiIp:$port/track/${track.id}.flac"
            val durationSec = if (track.durationMs > 0) track.durationMs / 1000 else 0
            val h = durationSec / 3600
            val m = (durationSec % 3600) / 60
            val s = durationSec % 60
            val duration = String.format("%d:%02d:%02d", h, m, s)
            val didl = buildDIDL(track, trackUrl, duration)
            sb.append("&lt;Track${i + 1}&gt;")
            sb.append("&lt;URL&gt;${trackUrl.escapeXml()}&lt;/URL&gt;")
            sb.append("&lt;Source&gt;OnlineMusic&lt;/Source&gt;")
            sb.append("&lt;Key&gt;&lt;/Key&gt;")
            sb.append("&lt;Id&gt;${track.id}&lt;/Id&gt;")
            sb.append("&lt;Metadata&gt;${didl.escapeXml()}&lt;/Metadata&gt;")
            sb.append("&lt;ChapterNumber&gt;0&lt;/ChapterNumber&gt;")
            sb.append("&lt;Chapters&gt;&lt;/Chapters&gt;")
            sb.append("&lt;/Track${i + 1}&gt;")
        }
        sb.append("&lt;/Tracks&gt;")
        sb.append("&lt;/PlayList&gt;")
        return sb.toString()
    }

    private fun buildDIDL(track: TrackInfo, trackUrl: String, duration: String): String {
        return """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"
xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
xmlns:song="www.wiimu.com/song/"
xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
<upnp:class>object.item.audioItem.musicTrack</upnp:class>
<item id="0">
<song:subid></song:subid>
<song:description></song:description>
<song:skiplimit>0</song:skiplimit>
<song:id>${track.id}</song:id>
<song:like>0</song:like>
<song:singerid>0</song:singerid>
<song:albumid>0</song:albumid>
<song:quality>0</song:quality>
<song:actualQuality>LOSSLESS</song:actualQuality>
<song:rate_hz>44100</song:rate_hz>
<song:format_s>16</song:format_s>
<song:bitrate>0</song:bitrate>
<res protocolInfo="http-get:*:audio/flac:DLNA.ORG_PN=FLAC;DLNA.ORG_OP=01;" duration="$duration">$trackUrl</res>
<dc:title>${track.title.orEmpty().escapeXml()}</dc:title>
<dc:creator>${track.artist.orEmpty().escapeXml()}</dc:creator>
<upnp:artist>${track.artist.orEmpty().escapeXml()}</upnp:artist>
<upnp:album>${track.albumTitle.orEmpty().escapeXml()}</upnp:album>
<upnp:albumArtURI></upnp:albumArtURI>
</item>
</DIDL-Lite>"""
    }


    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

}
