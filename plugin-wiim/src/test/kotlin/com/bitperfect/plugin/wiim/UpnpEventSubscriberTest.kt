package com.bitperfect.plugin.wiim

import com.bitperfect.core.output.OutputDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpnpEventSubscriberTest {

    private val testTarget = OutputDevice.Upnp(
        udn = "uuid:123",
        friendlyName = "Test WiiM",
        manufacturer = "WiiM",
        modelName = "Mini",
        deviceDescriptionUrl = "http://192.168.1.100:49152/description.xml",
        avTransportControlUrl = "http://192.168.1.100:49152/upnp/control/rendertransport1",
        renderingControlUrl = "http://192.168.1.100:49152/upnp/control/rendercontrol1",
        ipAddress = "192.168.1.100"
    )

    @Test
    fun testParseAvTransportLastChange() = runTest {
        val lastChangeXml = """
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING"/>
                <CurrentTrackMetaData val="&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot; xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot;&gt;&lt;item&gt;&lt;dc:title&gt;Test Title&lt;/dc:title&gt;&lt;upnp:artist&gt;Test Artist&lt;/upnp:artist&gt;&lt;upnp:album&gt;Test Album&lt;/upnp:album&gt;&lt;upnp:albumArtURI&gt;http://example.com/art.jpg&lt;/upnp:albumArtURI&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"/>
                <RelativeTimePosition val="00:01:30"/>
                <CurrentTrackDuration val="00:03:00"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        // Use reflection to call the private parseLastChange method
        val subscriber = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) {
    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}


        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state

    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}


        parseMethod.invoke(subscriberWithCallback, lastChangeXml)

        // Wait for coroutine to process
        Thread.sleep(500)

        val state = emittedState
        assertTrue("State should not be null", state != null)
        assertEquals(true, state?.isPlaying)
        assertEquals(90000L, state?.positionMs)
        assertEquals(180000L, state?.durationMs)
        assertEquals("Test Title", state?.title)
        assertEquals("Test Artist", state?.artist)
        assertEquals("Test Album", state?.album)
        assertEquals("http://example.com/art.jpg", state?.albumArtUrl)

    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}


    @Test
    fun testParseRenderingControlLastChange() = runTest {
        val lastChangeXml = """
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">
              <InstanceID val="0">
                <Volume channel="Master" val="75"/>
                <Mute channel="Master" val="1"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state

    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}


        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        parseMethod.invoke(subscriberWithCallback, lastChangeXml)

        // Wait for coroutine to process
        Thread.sleep(500)

        val state = emittedState
        assertTrue("State should not be null", state != null)
        assertEquals(75, state?.volume)
        assertEquals(true, state?.isMuted)
        assertNull(state?.isPlaying)

    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}


    @Test
    fun testParseLastChangeWithXXE() = runTest {
        val xxePayload = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <!DOCTYPE foo [
            <!ELEMENT foo ANY >
            <!ENTITY xxe SYSTEM "file:///etc/passwd" >]>
            <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
              <InstanceID val="0">
                <TransportState val="PLAYING &xxe;"/>
              </InstanceID>
            </Event>
        """.trimIndent()

        var emittedState: UpnpStateChange? = null
        val subscriberWithCallback = UpnpEventSubscriber(testTarget, "192.168.1.10", CoroutineScope(Dispatchers.Unconfined)) { state ->
            emittedState = state
        }

        val parseMethod = UpnpEventSubscriber::class.java.getDeclaredMethod("parseLastChange", String::class.java)
        parseMethod.isAccessible = true

        try {
            parseMethod.invoke(subscriberWithCallback, xxePayload)
        } catch (e: Exception) {
            // Depending on the exact exception, it might be caught by the try-catch block inside parseLastChange,
            // or bubble up as an InvocationTargetException.
        }

        // Wait for coroutine to process
        Thread.sleep(500)

        // The parser should fail parsing the DTD entity, and the exception should be caught silently by the
        // try-catch block inside parseLastChange. Thus, no state should be emitted.
        assertNull(emittedState)
    }
}
