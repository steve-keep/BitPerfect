package com.bitperfect.app.usb

import android.hardware.usb.UsbDeviceConnection
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class DefaultUsbTransportTest {
    @Test
    fun `nextTag increments successfully`() {
        val mockConnection = mock(UsbDeviceConnection::class.java)
        val transport = DefaultUsbTransport(mockConnection)

        val t1 = transport.nextTag()
        val t2 = transport.nextTag()
        val t3 = transport.nextTag()

        assertNotEquals(t1, t2)
        assertNotEquals(t2, t3)
        assertTrue(t2 > t1)
        assertTrue(t3 > t2)
    }
}
