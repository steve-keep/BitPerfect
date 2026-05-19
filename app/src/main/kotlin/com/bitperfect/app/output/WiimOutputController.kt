package com.bitperfect.app.output

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bitperfect.app.library.TrackInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class WiimOutputController(
    private val context: Context,
    private val target: OutputDevice.Upnp
) : OutputController {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var httpServer: FlacHttpServer? = null
    private var wifiIp: String? = null

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

    override suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long) {
        val trackList = tracks
        val index = startIndex
        wifiIp = getWifiIpAddress()
        if (wifiIp == null) {
            Log.w("WiimOutputController", "Not on Wi-Fi or unable to get IP, ignoring takeOver")
            return
        }

        httpServer?.stop()
        httpServer = FlacHttpServer(wifiIp!!, trackList)
        httpServer?.start(5000, false)

        val track = trackList.getOrNull(index) ?: return
        val url = "http://$wifiIp:${httpServer?.listeningPort}/track/${track.id}.flac"

        val didl = buildDidl(track, url)

        withContext(Dispatchers.IO) {
            val success = sendSoapAction(
                "SetAVTransportURI",
                """
                <InstanceID>0</InstanceID>
                <CurrentURI>$url</CurrentURI>
                <CurrentURIMetaData>${escapeXml(didl)}</CurrentURIMetaData>
                """.trimIndent()
            )

            if (success) {
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

                sendSoapAction(
                    "Play",
                    """
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                    """.trimIndent()
                )
            }
        }
    }

    override suspend fun play() {
        withContext(Dispatchers.IO) {
            sendSoapAction(
                "Play",
                """
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
                """.trimIndent()
            )
        }
    }

    override suspend fun pause() {
        withContext(Dispatchers.IO) {
            sendSoapAction(
                "Pause",
                """
                <InstanceID>0</InstanceID>
                """.trimIndent()
            )
        }
    }

    override suspend fun seekTo(positionMs: Long) {
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

    override suspend fun getPositionMs(): Long = withContext(Dispatchers.IO) {
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

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            sendSoapAction(
                "Stop",
                """
                <InstanceID>0</InstanceID>
                """.trimIndent()
            )
            httpServer?.stop()
        }
    }

    private fun buildDidl(track: TrackInfo, url: String): String {
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="${track.id}" parentID="0" restricted="1">
                    <dc:title>${escapeXml(track.title)}</dc:title>
                    <dc:creator>${escapeXml(track.artist)}</dc:creator>
                    <upnp:album>${escapeXml(track.albumTitle)}</upnp:album>
                    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                    <res protocolInfo="http-get:*:audio/flac:*">$url</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun sendSoapAction(action: String, body: String): Boolean {
        return sendSoapActionWithResponse(action, body) != null
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

    private inner class FlacHttpServer(val ip: String, val trackList: List<TrackInfo>) : NanoHTTPD(ip, 0) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            if (!uri.startsWith("/track/") || !uri.endsWith(".flac")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }

            val trackIdString = uri.substringAfter("/track/").substringBefore(".flac")
            val trackId = trackIdString.toLongOrNull() ?: -1L
            val track = trackList.find { it.id == trackId }
            if (track == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Track Not Found")
            }

            val filePath = track.filePath ?: track.dataPath
            val file = File(filePath)
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")
            }

            val rangeHeader = session.headers["range"]
            val fileLen = file.length()

            var startFrom: Long = 0
            var endAt: Long = -1
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val range = rangeHeader.substring(6).split("-")
                startFrom = try {
                    range[0].toLong()
                } catch (e: NumberFormatException) {
                    0
                }
                endAt = try {
                    if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else -1
                } catch (e: NumberFormatException) {
                    -1
                }
            }

            if (rangeHeader != null && startFrom >= 0) {
                if (startFrom >= fileLen) {
                    val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes 0-0/$fileLen")
                    return res
                }

                if (endAt < 0) {
                    endAt = fileLen - 1
                }

                var newLen = endAt - startFrom + 1
                if (newLen < 0) {
                    newLen = 0
                }

                val fis = FileInputStream(file)
                fis.skip(startFrom)

                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "audio/flac", fis, newLen)
                res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
                return res
            } else {
                val fis = FileInputStream(file)
                return newFixedLengthResponse(Response.Status.OK, "audio/flac", fis, fileLen)
            }
        }
    }
}
