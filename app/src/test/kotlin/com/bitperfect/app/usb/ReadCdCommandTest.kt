package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadCdCommandTest {
    private lateinit var transport: UsbTransport
    private lateinit var inEndpoint: UsbEndpoint
    private lateinit var outEndpoint: UsbEndpoint
    private lateinit var readCdCommand: ReadCdCommand

    @Before
    fun setUp() {
        transport = mock(UsbTransport::class.java)
        `when`(transport.nextTag()).thenReturn(1000)
        inEndpoint = mock(UsbEndpoint::class.java)
        outEndpoint = mock(UsbEndpoint::class.java)

        readCdCommand = ReadCdCommand(transport, outEndpoint, inEndpoint)
    }

    private fun setupMockTransfer(cswTag: Int = 1000) {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, cswTag)
        }
        `when`(transport.bulkTransferFully(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, cswTag)
        }
    }

    private fun handleMockTransfer(invocation: org.mockito.invocation.InvocationOnMock, cswTag: Int): Int {
        val buffer = invocation.arguments[1] as ByteArray
        val length = invocation.arguments[2] as Int

        if (length == 31) {
            // CBW
            return length
        } else if (length == 2352) {
            // Audio Data Single Phase
            val fakeData = ByteArray(2352) { it.toByte() }
            System.arraycopy(fakeData, 0, buffer, 0, 2352)
            return 2352
        } else if (length == 13) {
            // CSW
            val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
            cswBuffer.putInt(cswTag) // tag
            cswBuffer.putInt(0) // data residue
            cswBuffer.put(0.toByte()) // status success
            return length
        } else {
            return -1
        }
    }

    @Test
    fun `test matching tag returns data`() {
        setupMockTransfer(cswTag = 1000)
        val result = readCdCommand.execute(lba = 0, sectorCount = 1)
        assertNotNull(result)
    }

    @Test
    fun `test consecutive calls use different tags`() {
        `when`(transport.nextTag()).thenReturn(1001, 1002)
        val readCdCommand2 = ReadCdCommand(transport, outEndpoint, inEndpoint)

        setupMockTransfer(cswTag = 1001)
        val result1 = readCdCommand2.execute(lba = 0, sectorCount = 1)

        setupMockTransfer(cswTag = 1002)
        val result2 = readCdCommand2.execute(lba = 0, sectorCount = 1)

        assertNotNull(result1)
        assertNotNull(result2)
    }

    @Test
    fun `test mismatched tag returns null`() {
        setupMockTransfer(cswTag = 999)
        val result = readCdCommand.execute(lba = 0, sectorCount = 1)
        assertNull(result)
    }
}
