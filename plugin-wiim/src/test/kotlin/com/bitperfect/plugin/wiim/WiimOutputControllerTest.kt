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
        every { controller["sendLinkPlayCommand"](any<String>()) } returns true
        every { controller["sendSoapToQueue"](any<String>(), any<String>()) } returns "<response></response>"
        every { controller["fetchLinkPlay"](any<String>()) } returns "{}"
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
            controller["sendLinkPlayCommand"]("setPlayerCmd:next")
        }
    }

    @Test
    fun `skipPrev sends Previous SOAP action`() = runTest {
        controller.skipPrev()

        verify {
            controller["sendLinkPlayCommand"]("setPlayerCmd:prev")
        }
    }







    @Test
    fun `takeOver sends setPlayerCmd playlist and seek`() = runTest {
        io.mockk.every { controller["fetchLinkPlay"]("getPlayerStatus") } returns "{\"status\":\"play\"}"

        val tracks = emptyList<TrackInfo>()
        controller.takeOver(tracks, startIndex = 0, startPositionMs = 5000, playWhenReady = true)

        verifyOrder {
            controller["sendLinkPlayCommand"](match<String> { it.startsWith("setPlayerCmd:playlist:http://127.0.0.1:") && it.endsWith("/playlist.m3u8:0") })
            controller["fetchLinkPlay"]("getPlayerStatus")
            controller["sendLinkPlayCommand"]("setPlayerCmd:seek:5")
        }

        verify(exactly = 0) {
            controller["sendSoapToQueue"](any<String>(), any<String>())
        }
    }

}
