package com.bitperfect.app.output

import android.content.Context
import com.bitperfect.app.library.TrackInfo
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

        mockkStatic("com.bitperfect.app.output.SslUtilsKt")
        every { openTrustAllConnection(any()) } returns mockConnection

        // Mock getWifiIpAddress to return a dummy IP to avoid returning null and returning early
        every { controller["getWifiIpAddress"]() } returns "127.0.0.1"

        // Mock sendSoapActionWithResponse to always return a non-null string, meaning success
        every { controller["sendSoapActionWithResponse"](any<String>(), any<String>()) } returns mockResponse
    }

    @After
    fun teardown() {
        unmockkAll()
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
    fun `takeOver with multiple tracks sends correct sequence of SOAP actions`() = runTest {
        val tracks = listOf(
            TrackInfo(1L, "Track 1", 1, 1000L),
            TrackInfo(2L, "Track 2", 2, 2000L),
            TrackInfo(3L, "Track 3", 3, 3000L)
        )

        controller.takeOver(tracks, startIndex = 1, startPositionMs = 0L)

        // Give it some time to process
        // We verify sendSoapAction instead of sendSoapActionWithResponse directly
        // because it avoids the internal function calls issue in verifyOrder

        val calls = mutableListOf<String>()
        verify {
            controller["sendSoapActionWithResponse"](capture(calls), any<String>())
        }

        // We check if the actions were sent in order
        // RemoveAllTracksFromQueue -> SetAVTransportURI -> AddURIToQueue -> AddURIToQueue -> Seek -> Play
        val expectedActions = listOf("RemoveAllTracksFromQueue", "SetAVTransportURI", "AddURIToQueue", "AddURIToQueue", "Seek", "Play")
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
    }

    @Test
    fun `takeOver with single track sends correct sequence without AddURIToQueue or Seek`() = runTest {
        val tracks = listOf(
            TrackInfo(1L, "Track 1", 1, 1000L)
        )

        controller.takeOver(tracks, startIndex = 0, startPositionMs = 0L)

        val calls = mutableListOf<String>()
        verify {
            controller["sendSoapActionWithResponse"](capture(calls), any<String>())
        }

        // We check if the actions were sent in order
        val expectedActions = listOf("RemoveAllTracksFromQueue", "SetAVTransportURI", "Play")
        val actualActions = calls.filter { it in expectedActions }

        assert(actualActions == expectedActions) { "Expected: $expectedActions, but got: $actualActions" }

        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("AddURIToQueue", any<String>()) }
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("Seek", match<String> { it.contains("<Unit>TRACK_NR</Unit>") }) }
        verify(exactly = 0) { controller["sendSoapActionWithResponse"]("Seek", match<String> { it.contains("<Unit>REL_TIME</Unit>") }) }
    }
}
