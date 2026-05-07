package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UsbTransportTest {

    private lateinit var endpoint: UsbEndpoint

    @Before
    fun setup() {
        endpoint = mock(UsbEndpoint::class.java)
        `when`(endpoint.maxPacketSize).thenReturn(512)
    }

    /**
     * A fake transport that simulates returning specified sizes of data for each bulk transfer call.
     */
    class FakeUsbTransport(private val transferSizes: List<Int>) : UsbTransport {
        var callCount = 0

        override fun bulkTransfer(
            endpoint: UsbEndpoint,
            buffer: ByteArray,
            length: Int,
            timeout: Int
        ): Int {
            throw UnsupportedOperationException("Should not be called")
        }

        override fun bulkTransfer(
            endpoint: UsbEndpoint,
            buffer: ByteArray,
            offset: Int,
            length: Int,
            timeout: Int
        ): Int {
            if (callCount >= transferSizes.size) {
                return -1 // simulate error if called more times than expected
            }
            val size = transferSizes[callCount]
            callCount++
            return size
        }
    }

    @Test
    fun `bulkTransferFully loops until maxLength is reached`() {
        // 3 calls, each returning a full chunk (512 bytes)
        val fakeTransport = FakeUsbTransport(listOf(512, 512, 512))
        val buffer = ByteArray(1536)

        val totalRead = fakeTransport.bulkTransferFully(endpoint, buffer, 1536, 1000)

        assertEquals(1536, totalRead)
        assertEquals(3, fakeTransport.callCount)
    }

    @Test
    fun `bulkTransferFully does NOT break on short packets mid-transfer`() {
        // Max packet size is 512.
        // The transport returns 512, then a short packet of 200, then 512, then 312.
        // Total = 512 + 200 + 512 + 312 = 1536
        val fakeTransport = FakeUsbTransport(listOf(512, 200, 512, 312))
        val buffer = ByteArray(1536)

        val totalRead = fakeTransport.bulkTransferFully(endpoint, buffer, 1536, 1000)

        assertEquals(1536, totalRead)
        assertEquals(4, fakeTransport.callCount)
    }

    @Test
    fun `bulkTransferFully breaks on ZLP (Zero Length Packet)`() {
        // The transport returns 512, then a ZLP (0).
        val fakeTransport = FakeUsbTransport(listOf(512, 0))
        val buffer = ByteArray(1536)

        val totalRead = fakeTransport.bulkTransferFully(endpoint, buffer, 1536, 1000)

        assertEquals(512, totalRead)
        assertEquals(2, fakeTransport.callCount)
    }

    @Test
    fun `bulkTransferFully returns totalRead when error occurs mid-transfer`() {
        // The transport returns 512, then error (-1)
        val fakeTransport = FakeUsbTransport(listOf(512, -1))
        val buffer = ByteArray(1536)

        val totalRead = fakeTransport.bulkTransferFully(endpoint, buffer, 1536, 1000)

        assertEquals(512, totalRead)
        assertEquals(2, fakeTransport.callCount)
    }

    @Test
    fun `bulkTransferFully returns error when error occurs on first call`() {
        // The transport returns error (-1) immediately
        val fakeTransport = FakeUsbTransport(listOf(-1))
        val buffer = ByteArray(1536)

        val totalRead = fakeTransport.bulkTransferFully(endpoint, buffer, 1536, 1000)

        assertEquals(-1, totalRead)
        assertEquals(1, fakeTransport.callCount)
    }
}
