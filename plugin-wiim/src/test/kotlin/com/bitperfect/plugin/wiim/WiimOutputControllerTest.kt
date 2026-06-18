package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice

import android.content.Context
import com.bitperfect.core.output.TrackInfo
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verifyOrder
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WiimOutputControllerTest {

    private lateinit var context: Context
    private lateinit var controller: WiimOutputController
    private lateinit var mockConnection: HttpURLConnection

    @Before
    fun setup() {
        context = mockk<Context>(relaxed = true)
        val target = OutputDevice.Upnp(
            udn = "uuid:123",
            friendlyName = "WiiM",
            manufacturer = "LinkPlay",
            modelName = "Mini",
            deviceDescriptionUrl = "http://192.168.1.100:49152/description.xml",
            avTransportControlUrl = "http://192.168.1.100:49152/upnp/control/rendertransport1",
            renderingControlUrl = null,
            ipAddress = "192.168.1.100"
        )
        controller = spyk(WiimOutputController(context, target), recordPrivateCalls = true)

        mockConnection = mockk<HttpURLConnection>(relaxed = true)
        every { mockConnection.responseCode } returns 200
        val mockResponse = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"></u:SetAVTransportURIResponse></s:Body></s:Envelope>"""
        every { mockConnection.inputStream } answers { ByteArrayInputStream(mockResponse.toByteArray()) }
        every { mockConnection.outputStream } returns mockk(relaxed = true)
        every { mockConnection.errorStream } returns null

        // Store current mockConnection in companion object so the static URLStreamHandlerFactory can use it
        Companion.currentMockConnection = mockConnection

        // Mock getWifiIpAddress to return a dummy IP to avoid returning null and returning early
        every { controller["getWifiIpAddress"]() } returns "127.0.0.1"

        // Mock sendSoapActionWithResponse to always return a non-null string, meaning success
        every { controller["sendSoapActionWithResponse"](any<String>(), any<String>()) } returns mockResponse
    }

    @After
    fun teardown() {
        unmockkAll()
        Companion.currentMockConnection = null
    }

    companion object {
        var currentMockConnection: HttpURLConnection? = null

        var defaultHttpHandler: java.net.URLStreamHandler? = null

        init {
            // Retrieve default HTTP handler BEFORE setting the factory
            try {
                val dummy = java.net.URL("http://example.com")
                val handlerField = java.net.URL::class.java.getDeclaredField("handler")
                handlerField.isAccessible = true
                defaultHttpHandler = handlerField.get(dummy) as java.net.URLStreamHandler
            } catch (e: Exception) {
                // Ignore
            }

            try {
                java.net.URL.setURLStreamHandlerFactory { protocol ->
                    if (protocol == "http") {
                        object : java.net.URLStreamHandler() {
                            override fun openConnection(u: java.net.URL): java.net.URLConnection {
                                if (u.host == "192.168.1.100" && u.path.contains("httpapi.asp")) {
                                    val mock = currentMockConnection
                                    if (mock != null) return mock
                                }
                                val handler = defaultHttpHandler
                                if (handler != null) {
                                    try {
                                        val m = java.net.URLStreamHandler::class.java.getDeclaredMethod("openConnection", java.net.URL::class.java)
                                        m.isAccessible = true
                                        return m.invoke(handler, u) as java.net.URLConnection
                                    } catch (e: Exception) {
                                        throw RuntimeException("Fallback connection failed for URL $u", e)
                                    }
                                }

                                // On some JVMs, we can't extract the default HTTP handler reflectively due to modularity rules.
                                // If defaultHttpHandler is null, we can do a naive socket GET just to make NanoHTTPD tests pass.
                                if (u.host == "127.0.0.1") {
                                    return object : java.net.HttpURLConnection(u) {
                                        var content: ByteArray? = null
                                        override fun connect() {}
                                        override fun disconnect() {}
                                        override fun usingProxy() = false
                                        override fun getResponseCode() = 200
                                        override fun getInputStream(): java.io.InputStream {
                                            if (content == null) {
                                                try {
                                                    val socket = java.net.Socket(u.host, u.port)
                                                    val req = "GET ${u.path} HTTP/1.0\r\nHost: ${u.host}\r\n\r\n"
                                                    socket.outputStream.write(req.toByteArray())
                                                    socket.outputStream.flush()
                                                    val bytes = socket.inputStream.readBytes()
                                                    socket.close()
                                                    // strip HTTP headers
                                                    val str = String(bytes)
                                                    val body = str.substring(str.indexOf("\r\n\r\n") + 4)
                                                    content = body.toByteArray()
                                                } catch (e: Exception) {
                                                    throw java.io.IOException(e)
                                                }
                                            }
                                            return java.io.ByteArrayInputStream(content)
                                        }
                                    }
                                }

                                throw UnsupportedOperationException("Not mocked and no default handler: " + u)
                            }
                        }
                    } else null
                }
            } catch (e: Error) {
                // Ignore if already set
            }
        }
    }

    @Test
    fun `skipNext sends Next SOAP action`() = runTest {
        controller.skipNext()

        verify {
            controller["sendSoapActionWithResponse"](
                "Next",
                match<String> { it.contains("<InstanceID>0</InstanceID>") }
            )
        }
    }

    @Test
    fun `skipPrev sends Previous SOAP action`() = runTest {
        controller.skipPrev()

        verify {
            controller["sendSoapActionWithResponse"](
                "Previous",
                match<String> { it.contains("<InstanceID>0</InstanceID>") }
            )
        }
    }

    @Test
    fun `takeOver with tracks serves M3U8 playlist correctly via FlacHttpServer`() = runTest {
        val tracks = listOf(
            TrackInfo(id = 1L, title = "Track 1", artist = "Artist A", albumTitle = "", durationMs = 1500L, trackNumber = 1, filePath = null, dataPath = null, albumId = -1L),
            TrackInfo(id = 2L, title = "Track 2", artist = "Artist B", albumTitle = "", durationMs = 2500L, trackNumber = 2, filePath = null, dataPath = null, albumId = -1L),
            TrackInfo(id = 3L, title = "Track 3", artist = "Artist C", albumTitle = "", durationMs = 3500L, trackNumber = 3, filePath = null, dataPath = null, albumId = -1L)
        )

        controller.takeOver(tracks, startIndex = 0, startPositionMs = 0L)

        // Give the local server a moment to spin up properly
        kotlinx.coroutines.delay(100)

        // We can capture the URL sent to sendLinkPlayCommand to find the playlist port
        val urlSlot = io.mockk.slot<String>()
        verify {
            controller["sendLinkPlayCommand"](capture(urlSlot))
        }

        val url = urlSlot.captured
        assert(url.startsWith("setPlayerCmd:playlist:"))

        val playlistUrlEncoded = url.substringAfter("setPlayerCmd:playlist:")
        val playlistUrl = java.net.URLDecoder.decode(playlistUrlEncoded, "UTF-8")

        assert(playlistUrl.endsWith("/playlist.m3u8"))

        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("SetAVTransportURI", any<String>()) }

        // Make a real HTTP request to verify the playlist content
        val parsedUrl = java.net.URL(playlistUrl)
        val m3u8Content = parsedUrl.readText()

        val port = parsedUrl.port
        val expected = """
            #EXTM3U
            #EXTINF:1,Artist A - Track 1
            http://127.0.0.1:$port/track/1.flac
            #EXTINF:2,Artist B - Track 2
            http://127.0.0.1:$port/track/2.flac
            #EXTINF:3,Artist C - Track 3
            http://127.0.0.1:$port/track/3.flac

        """.trimIndent()

        assert(m3u8Content == expected) { "Expected:\n$expected\nGot:\n$m3u8Content" }
    }

    @Test
    fun `takeOver sends correct sequence of SOAP actions using playlist URI`() = runTest {
        val tracks = listOf(
            TrackInfo(id = 1L, title = "Track 1", artist = "", albumTitle = "", durationMs = 1000L, trackNumber = 1, filePath = null, dataPath = null, albumId = -1L),
            TrackInfo(id = 2L, title = "Track 2", artist = "", albumTitle = "", durationMs = 2000L, trackNumber = 2, filePath = null, dataPath = null, albumId = -1L),
            TrackInfo(id = 3L, title = "Track 3", artist = "", albumTitle = "", durationMs = 3000L, trackNumber = 3, filePath = null, dataPath = null, albumId = -1L)
        )

        // Mock sendLinkPlayCommand to always return true, simulating success
        every { controller["sendLinkPlayCommand"](any<String>()) } returns true

        controller.takeOver(tracks, startIndex = 1, startPositionMs = 0L)

        verify { controller["sendLinkPlayCommand"](match<String> { it.startsWith("setPlayerCmd:playlist:") }) }

        val calls = mutableListOf<String>()
        verify {
            controller["sendSoapActionWithResponse"](capture(calls), any<String>())
        }

        // We check if the actions were sent in order
        // Seek
        val expectedActions = listOf("Seek")
        val actualActions = calls.filter { it in expectedActions }

        assert(actualActions == expectedActions) { "Expected: $expectedActions, but got: $actualActions" }

        // Also check the specific Seek target
        verify {
            controller["sendSoapActionWithResponse"](
                "Seek",
                match<String> { it.contains("<Unit>TRACK_NR</Unit>") && it.contains("<Target>2</Target>") }
            )
        }

        // No REL_TIME Seek expected because startPositionMs = 0
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("Seek", match<String> { it.contains("<Unit>REL_TIME</Unit>") }) }

        // No Play expected because setPlayerCmd:playlist automatically plays
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("Play", any<String>()) }

        // Ensure the old queue clear method isn't sent
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("RemoveAllTracksFromQueue", any<String>()) }
        // Ensure Sonos extension isn't used
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("AddURIToQueue", any<String>()) }
    }

    @Test
    fun `polling updates metadata from hex-encoded JSON`() = runTest {
        val mockJson = """
            {
                "status": "play",
                "plicurr": "2",
                "Title": "54657374205469746C65",
                "Artist": "5465737420417274697374",
                "Album": "5465737420416C62756D"
            }
        """.trimIndent()

        // We need to re-configure the mockConnection stream for this specific test
        every { mockConnection.inputStream } returns ByteArrayInputStream(mockJson.toByteArray())

        // Call private startPolling via reflection
        controller.javaClass.getDeclaredMethod("startPolling").apply {
            isAccessible = true
            invoke(controller)
        }

        // Give coroutine time to poll
        kotlinx.coroutines.delay(100)

        // Wait for values to be set
        var attempts = 0
        while (controller.currentTitle.value != "Test Title" && attempts < 30) {
            kotlinx.coroutines.delay(100)
            attempts++
        }

        assert(controller.currentTrackIndex.value == 2) { "Expected 2, got ${controller.currentTrackIndex.value}" }
        assert(controller.currentTitle.value == "Test Title") { "Expected Test Title, got ${controller.currentTitle.value}" }
        assert(controller.currentArtist.value == "Test Artist") { "Expected Test Artist, got ${controller.currentArtist.value}" }
        assert(controller.currentAlbum.value == "Test Album") { "Expected Test Album, got ${controller.currentAlbum.value}" }
        assert(controller.isPlaying.value) { "Expected true, got ${controller.isPlaying.value}" }

        // Cleanup
        controller.javaClass.getDeclaredMethod("stopPolling").apply {
            isAccessible = true
            invoke(controller)
        }

        // Wait for coroutine to finish cancellation
        val jobField = controller.javaClass.getDeclaredField("pollingJob")
        jobField.isAccessible = true
        (jobField.get(controller) as? kotlinx.coroutines.Job)?.join()
    }
}
