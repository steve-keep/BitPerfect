package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadTocCommandTest {

    private lateinit var transport: UsbTransport
    private lateinit var inEndpoint: UsbEndpoint
    private lateinit var outEndpoint: UsbEndpoint
    private lateinit var readTocCommand: ReadTocCommand

    @Before
    fun setUp() {
        transport = mock(UsbTransport::class.java)
        `when`(transport.nextTag()).thenReturn(3)
        inEndpoint = mock(UsbEndpoint::class.java)
        outEndpoint = mock(UsbEndpoint::class.java)

        readTocCommand = ReadTocCommand(transport, outEndpoint, inEndpoint)
    }

    private fun setupMockTransfer(track1Lba: Int, cswTag: Int = 3) {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, track1Lba, cswTag)
        }
        `when`(transport.bulkTransferFully(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, track1Lba, cswTag)
        }
    }

    private fun handleMockTransfer(invocation: org.mockito.invocation.InvocationOnMock, track1Lba: Int, cswTag: Int): Int {
        val buffer = invocation.arguments[1] as ByteArray
        val length = invocation.arguments[2] as Int

        if (length == 31) {
            // CBW
            return length
        } else if (length == 804) {
            // TOC Data Single Phase
            val fakeData = createFakeTocData(track1Lba)
            val toCopy = Math.min(length, fakeData.size)
            System.arraycopy(fakeData, 0, buffer, 0, toCopy)
            return toCopy
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

    private fun createFakeTocData(track1Lba: Int): ByteArray {
        val tocData = ByteArray(804)
        val dataBuffer = ByteBuffer.wrap(tocData).order(ByteOrder.BIG_ENDIAN)

        // 3 tracks + 1 lead-out
        val entryCount = 4
        val tocDataLength = 2 + (entryCount * 8)
        dataBuffer.putShort(tocDataLength.toShort())
        dataBuffer.put(1.toByte()) // first track
        dataBuffer.put(3.toByte()) // last track

        val lbas = listOf(
            track1Lba,
            track1Lba + 10000,
            track1Lba + 20000
        )
        val leadOutLba = track1Lba + 30000

        dataBuffer.position(4)

        // Track 1
        dataBuffer.put(0)
        dataBuffer.put(0x10)
        dataBuffer.put(1)
        dataBuffer.put(0)
        dataBuffer.putInt(lbas[0])

        // Track 2
        dataBuffer.put(0)
        dataBuffer.put(0x10)
        dataBuffer.put(2)
        dataBuffer.put(0)
        dataBuffer.putInt(lbas[1])

        // Track 3
        dataBuffer.put(0)
        dataBuffer.put(0x10)
        dataBuffer.put(3)
        dataBuffer.put(0)
        dataBuffer.putInt(lbas[2])

        // Lead-out
        dataBuffer.put(0)
        dataBuffer.put(0x10)
        dataBuffer.put(0xAA.toByte())
        dataBuffer.put(0)
        dataBuffer.putInt(leadOutLba)

        return tocData
    }

    @Test
    fun `test normalises zero based LBA`() {
        setupMockTransfer(0)

        val result = readTocCommand.execute(3)

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.tracks.size)

        // All tracks and lead-out should be offset by 150
        assertEquals(150, toc.tracks[0].lba)
        assertEquals(10150, toc.tracks[1].lba)
        assertEquals(20150, toc.tracks[2].lba)
        assertEquals(30150, toc.leadOutLba)
    }

    @Test
    fun `test standard LBA not modified`() {
        setupMockTransfer(150)

        val result = readTocCommand.execute(3)

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.tracks.size)

        // Standard drive, no offset applied
        assertEquals(150, toc.tracks[0].lba)
        assertEquals(10150, toc.tracks[1].lba)
        assertEquals(20150, toc.tracks[2].lba)
        assertEquals(30150, toc.leadOutLba)
    }

    @Test
    fun `test unexpected LBA not modified`() {
        setupMockTransfer(75)

        val result = readTocCommand.execute(3)

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.tracks.size)

        // Unexpected drive LBA, no offset applied
        assertEquals(75, toc.tracks[0].lba)
        assertEquals(10075, toc.tracks[1].lba)
        assertEquals(20075, toc.tracks[2].lba)
        assertEquals(30075, toc.leadOutLba)
    }

    @Test
    fun `test mismatched tag returns null`() {
        // Tag is configured to 3 in setUp, return CSW with tag 99
        setupMockTransfer(150, cswTag = 99)

        val result = readTocCommand.execute(3)
        org.junit.Assert.assertNull(result)
    }
}
