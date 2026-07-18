package com.bitperfect.plugin.wiim

import com.bitperfect.core.WiimDebugLogger
import com.bitperfect.core.output.OutputDevice
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class UpnpStateChange(
    val isPlaying: Boolean? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val volume: Int? = null,
    val isMuted: Boolean? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUrl: String? = null
)

class UpnpEventSubscriber(
    private val target: OutputDevice.Upnp,
    private val wifiIp: String,
    private val scope: CoroutineScope,
    private val onStateChange: (UpnpStateChange) -> Unit
) {

    private var server: NotifyServer? = null
    var eventsActive: Boolean = false
        private set

    private var avTransportSid: String? = null
    private var renderingControlSid: String? = null

    private var avTransportJob: Job? = null
    private var renderingControlJob: Job? = null

    private val callbackPath = "/upnp/event"

    fun start() {
        try {
            server = NotifyServer(0)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val port = server?.listeningPort ?: throw Exception("Failed to bind port")

            val callbackUrl = "<http://$wifiIp:$port$callbackPath>"

            avTransportJob = scope.launch(Dispatchers.IO) {
                subscribeService(target.avTransportControlUrl, callbackUrl, isAvTransport = true)
            }
            renderingControlJob = scope.launch(Dispatchers.IO) {
                subscribeService(target.renderingControlUrl, callbackUrl, isAvTransport = false)
            }

            eventsActive = true
        } catch (e: Exception) {
            eventsActive = false
            WiimDebugLogger.log("UpnpEventSubscriber failed to start: ${e.message}")
            eventsActive = false
        }
    }

    suspend fun stop() {
        eventsActive = false
        avTransportJob?.cancel()
        renderingControlJob?.cancel()

        withContext(Dispatchers.IO) {
            unsubscribeService(target.avTransportControlUrl, avTransportSid)
            unsubscribeService(target.renderingControlUrl, renderingControlSid)
            server?.stop()
        }
    }

    private suspend fun subscribeService(serviceUrl: String?, callbackUrl: String, isAvTransport: Boolean) {
        if (!eventsActive) return
        if (serviceUrl.isNullOrBlank()) return

        val sid = if (isAvTransport) avTransportSid else renderingControlSid

        try {
            val url = URL(serviceUrl)
            val host = url.host
            val port = if (url.port > 0) url.port else 80
            val path = url.path.takeIf { it.isNotEmpty() } ?: "/"

            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000

            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val `in` = socket.getInputStream().bufferedReader(Charsets.UTF_8)

            if (sid == null) {
                out.write("SUBSCRIBE $path HTTP/1.1\r\n")
                out.write("Host: $host:$port\r\n")
                out.write("CALLBACK: $callbackUrl\r\n")
                out.write("NT: upnp:event\r\n")
                out.write("TIMEOUT: Second-1800\r\n")
                out.write("\r\n")
            } else {
                out.write("SUBSCRIBE $path HTTP/1.1\r\n")
                out.write("Host: $host:$port\r\n")
                out.write("SID: $sid\r\n")
                out.write("TIMEOUT: Second-1800\r\n")
                out.write("\r\n")
            }
            out.flush()

            val lines = mutableListOf<String>()
            var line = `in`.readLine()
            while (line != null && line.isNotBlank()) {
                lines.add(line)
                line = `in`.readLine()
            }
            socket.close()

            val statusLine = lines.firstOrNull() ?: ""
            if (!statusLine.contains("200 OK", ignoreCase = true)) {
                WiimDebugLogger.log("SUBSCRIBE failed for $path: $statusLine")
                if (sid != null) {
                    if (isAvTransport) avTransportSid = null else renderingControlSid = null
                    subscribeService(serviceUrl, callbackUrl, isAvTransport)
                } else {
                    eventsActive = false
                }
                return
            }

            var newSid: String? = null
            var timeoutStr: String? = null

            for (header in lines) {
                if (header.startsWith("SID:", ignoreCase = true)) {
                    newSid = header.substringAfter(":").trim()
                }
                if (header.startsWith("TIMEOUT:", ignoreCase = true)) {
                    timeoutStr = header.substringAfter(":").trim()
                }
            }

            if (newSid != null) {
                if (isAvTransport) avTransportSid = newSid else renderingControlSid = newSid
            }

            var timeoutSeconds = 1800L
            if (timeoutStr != null && timeoutStr.startsWith("Second-", ignoreCase = true)) {
                timeoutStr.substringAfter("-").toLongOrNull()?.let {
                    timeoutSeconds = it
                }
            }

            WiimDebugLogger.log("SUBSCRIBE success for $path, SID=$newSid, timeout=$timeoutSeconds")

            val renewalTime = (timeoutSeconds * 1000 * 0.75).toLong()
            delay(renewalTime)
            subscribeService(serviceUrl, callbackUrl, isAvTransport)

        } catch (e: Exception) {
            WiimDebugLogger.log("SUBSCRIBE error for $serviceUrl: ${e.message}")
            if (sid == null) {
                eventsActive = false
            } else {
                delay(10000)
                subscribeService(serviceUrl, callbackUrl, isAvTransport)
            }
        }
    }

    private fun unsubscribeService(serviceUrl: String?, sid: String?) {
        if (serviceUrl.isNullOrBlank() || sid.isNullOrBlank()) return
        try {
            val url = URL(serviceUrl)
            val host = url.host
            val port = if (url.port > 0) url.port else 80
            val path = url.path.takeIf { it.isNotEmpty() } ?: "/"

            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000

            val out = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            out.write("UNSUBSCRIBE $path HTTP/1.1\r\n")
            out.write("Host: $host:$port\r\n")
            out.write("SID: $sid\r\n")
            out.write("\r\n")
            out.flush()
            socket.close()
        } catch (e: Exception) {
            WiimDebugLogger.log("UNSUBSCRIBE error for $serviceUrl: ${e.message}")
        }
    }

    private inner class NotifyServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method.name == "NOTIFY" && session.uri == callbackPath) {
                try {
                    val nt = session.headers["nt"] ?: session.headers["NT"]
                    if (nt?.equals("upnp:event", ignoreCase = true) == true) {
                        val map = HashMap<String, String>()
                        session.parseBody(map)
                        val postData = map["postData"]

                        if (!postData.isNullOrBlank()) {
                            parseNotifyBody(postData)
                        }
                    }
                } catch (e: Exception) {
                    WiimDebugLogger.log("Error parsing NOTIFY: ${e.message}")
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun parseNotifyBody(body: String) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            val parser = factory.newPullParser()
            parser.setInput(StringReader(body))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("LastChange", ignoreCase = true)) {
                    val lastChangeXml = parser.nextText()
                    if (lastChangeXml.isNotBlank()) {
                        parseLastChange(lastChangeXml)
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            WiimDebugLogger.log("Error parsing NOTIFY body: ${e.message}")
        }
    }

    private fun parseLastChange(xml: String) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var isPlaying: Boolean? = null
            var positionMs: Long? = null
            var durationMs: Long? = null
            var volume: Int? = null
            var isMuted: Boolean? = null
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var albumArtUrl: String? = null

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "TransportState" -> {
                            val valAttr = parser.getAttributeValue(null, "val")
                            if (valAttr != null) {
                                isPlaying = when (valAttr) {
                                    "PLAYING" -> true
                                    "PAUSED_PLAYBACK", "STOPPED" -> false
                                    else -> null
                                }
                            }
                        }
                        "CurrentTrackMetaData", "AVTransportURIMetaData" -> {
                            val didlXml = parser.getAttributeValue(null, "val")
                            if (!didlXml.isNullOrBlank()) {
                                val didlData = parseDidlLite(didlXml)
                                title = title ?: didlData.title
                                artist = artist ?: didlData.artist
                                album = album ?: didlData.album
                                albumArtUrl = albumArtUrl ?: didlData.albumArtUrl
                            }
                        }
                        "RelativeTimePosition", "AbsoluteTimePosition" -> {
                            val valAttr = parser.getAttributeValue(null, "val")
                            if (valAttr != null) {
                                val parsed = parseTimeToMs(valAttr)
                                if (parsed != null) positionMs = parsed
                            }
                        }
                        "CurrentTrackDuration" -> {
                            val valAttr = parser.getAttributeValue(null, "val")
                            if (valAttr != null) {
                                val parsed = parseTimeToMs(valAttr)
                                if (parsed != null) durationMs = parsed
                            }
                        }
                        "Volume" -> {
                            val channel = parser.getAttributeValue(null, "channel")
                            if (channel.equals("Master", ignoreCase = true)) {
                                val valAttr = parser.getAttributeValue(null, "val")
                                valAttr?.toIntOrNull()?.let { volume = it }
                            }
                        }
                        "Mute" -> {
                            val channel = parser.getAttributeValue(null, "channel")
                            if (channel.equals("Master", ignoreCase = true)) {
                                val valAttr = parser.getAttributeValue(null, "val")
                                if (valAttr != null) {
                                    isMuted = (valAttr == "1")
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (isPlaying != null || positionMs != null || durationMs != null || volume != null || isMuted != null || title != null || artist != null || album != null || albumArtUrl != null) {
                val stateChange = UpnpStateChange(
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    volume = volume,
                    isMuted = isMuted,
                    title = title,
                    artist = artist,
                    album = album,
                    albumArtUrl = albumArtUrl
                )
                WiimDebugLogger.log("Parsed UpnpStateChange: $stateChange")
                scope.launch(Dispatchers.IO) {
                    onStateChange(stateChange)
                }
            }

        } catch (e: Exception) {
            WiimDebugLogger.log("Error parsing LastChange XML: ${e.message}")
        }
    }

    private data class DidlData(
        var title: String? = null,
        var artist: String? = null,
        var album: String? = null,
        var albumArtUrl: String? = null
    )

    private fun parseDidlLite(xml: String): DidlData {
        val result = DidlData()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val localName = parser.name?.substringAfterLast(":") ?: ""
                    when {
                        localName.equals("title", ignoreCase = true) -> result.title = parser.nextText()
                        localName.equals("artist", ignoreCase = true) -> result.artist = parser.nextText()
                        localName.equals("album", ignoreCase = true) -> result.album = parser.nextText()
                        localName.equals("albumArtURI", ignoreCase = true) -> {
                            val url = parser.nextText()
                            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                                if (url != "un_known") {
                                    result.albumArtUrl = url
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            WiimDebugLogger.log("Error parsing DIDL-Lite: ${e.message}")
        }
        return result
    }

    private fun parseTimeToMs(timeStr: String): Long? {
        if (timeStr.isBlank() || timeStr.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        return try {
            val parts = timeStr.split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    (h * 3600 + m * 60 + s) * 1000
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
