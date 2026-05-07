package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UsbTransportTest {

    private class FakeUsbTransport(
        private val chunks: List<ByteArray>,
        private val hardErrors: Map<Int, Int> = emptyMap() // Map of call index to error code (e.g. -1)
    ) : UsbTransport {
        var callCount = 0

        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
            val error = hardErrors[callCount]
            if (error != null) {
                callCount++
                return error
            }

            if (callCount >= chunks.size) {
                // If we ran out of chunks and still called, simulate timeout or 0 bytes read
                return 0
            }

            val chunk = chunks[callCount]
            callCount++

            // In USB bulk transfer, if a chunk is empty, we just simulate a ZLP (return 0)
            if (chunk.isEmpty()) {
                return 0
            }

            // In reality, minOf should be used to not overflow 'buffer', but for simulation
            // we assume the chunk size <= length.
            val toCopy = minOf(chunk.size, length)
            System.arraycopy(chunk, 0, buffer, 0, toCopy)
            return toCopy
        }
    }

    private fun createMockEndpoint(maxPacketSize: Int): UsbEndpoint {
        val endpoint = mock(UsbEndpoint::class.java)
        `when`(endpoint.maxPacketSize).thenReturn(maxPacketSize)
        return endpoint
    }

    @Test
    fun `test bulkTransferFully with exact read (no ZLP)`() {
        val endpoint = createMockEndpoint(512)
        val chunk1 = ByteArray(512) { 1 }
        val chunk2 = ByteArray(512) { 2 }
        val transport = FakeUsbTransport(listOf(chunk1, chunk2))

        val buffer = ByteArray(1024)
        val read = transport.bulkTransferFully(endpoint, buffer, 1024, 1000)

        assertEquals(1024, read)
        val expected = ByteArray(1024)
        System.arraycopy(chunk1, 0, expected, 0, 512)
        System.arraycopy(chunk2, 0, expected, 512, 512)
        assertArrayEquals(expected, buffer)
    }

    @Test
    fun `test bulkTransferFully with short final packet`() {
        val endpoint = createMockEndpoint(512)
        val chunk1 = ByteArray(512) { 1 }
        val chunk2 = ByteArray(512) { 2 }
        val chunk3 = ByteArray(512) { 3 }
        val chunk4 = ByteArray(512) { 4 }
        val chunk5 = ByteArray(304) { 5 }
        val transport = FakeUsbTransport(listOf(chunk1, chunk2, chunk3, chunk4, chunk5))

        val buffer = ByteArray(2352)
        val read = transport.bulkTransferFully(endpoint, buffer, 2352, 1000)

        assertEquals(2352, read)
        val expected = ByteArray(2352)
        System.arraycopy(chunk1, 0, expected, 0, 512)
        System.arraycopy(chunk2, 0, expected, 512, 512)
        System.arraycopy(chunk3, 0, expected, 1024, 512)
        System.arraycopy(chunk4, 0, expected, 1536, 512)
        System.arraycopy(chunk5, 0, expected, 2048, 304)
        assertArrayEquals(expected, buffer)
    }

    @Test
    fun `test bulkTransferFully with ZLP termination`() {
        val endpoint = createMockEndpoint(512)
        val chunk1 = ByteArray(512) { 1 }
        val chunk2 = ByteArray(512) { 2 }
        val zlp = ByteArray(0)
        // Simulate reading up to 2048, but device sends ZLP after 1024
        val transport = FakeUsbTransport(listOf(chunk1, chunk2, zlp))

        val buffer = ByteArray(2048)
        val read = transport.bulkTransferFully(endpoint, buffer, 2048, 1000)

        assertEquals(1024, read)
        val expected = ByteArray(2048) // remainder should be 0
        System.arraycopy(chunk1, 0, expected, 0, 512)
        System.arraycopy(chunk2, 0, expected, 512, 512)
        assertArrayEquals(expected, buffer)
    }

    @Test
    fun `test bulkTransferFully hard error on first call returns -1`() {
        val endpoint = createMockEndpoint(512)
        val transport = FakeUsbTransport(
            chunks = listOf(),
            hardErrors = mapOf(0 to -1)
        )

        val buffer = ByteArray(1024)
        val read = transport.bulkTransferFully(endpoint, buffer, 1024, 1000)

        assertEquals(-1, read)
    }

    @Test
    fun `test bulkTransferFully hard error mid-transfer returns bytes read so far`() {
        val endpoint = createMockEndpoint(512)
        val chunk1 = ByteArray(512) { 1 }
        val transport = FakeUsbTransport(
            chunks = listOf(chunk1),
            hardErrors = mapOf(1 to -1) // Error on second call
        )

        val buffer = ByteArray(1024)
        val read = transport.bulkTransferFully(endpoint, buffer, 1024, 1000)

        assertEquals(512, read)
        val expected = ByteArray(1024)
        System.arraycopy(chunk1, 0, expected, 0, 512)
        assertArrayEquals(expected, buffer)
    }
}
