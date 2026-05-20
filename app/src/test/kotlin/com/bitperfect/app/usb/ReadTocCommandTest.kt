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

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    private fun handleMockTransfer(invocation: org.mockito.invocation.InvocationOnMock, track1Lba: Int, cswTag: Int, isCdExtra: Boolean = false, shortReadSession1: Boolean = false): Int {
        val buffer = invocation.arguments[1] as ByteArray
        val length = invocation.arguments[2] as Int

        if (length == 31) {
            // CBW
            return length
        } else if (length == 804) {
            // TOC Data Single Phase
            val fakeData = createFakeTocData(track1Lba, isCdExtra)
            val toCopy = Math.min(length, fakeData.size)
            System.arraycopy(fakeData, 0, buffer, 0, toCopy)
            return toCopy
        } else if (length == 2048) {
            // Full TOC Data
            val fakeData = createFullTocData(track1Lba, isCdExtra)
            val toCopy = if (shortReadSession1) 12 else Math.min(length, fakeData.size)
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

    private fun setupMockTransferWithCdExtra(track1Lba: Int, shortReadSession1: Boolean = false) {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, track1Lba, 3, isCdExtra = true, shortReadSession1 = shortReadSession1)
        }
        `when`(transport.bulkTransferFully(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            handleMockTransfer(invocation, track1Lba, 3, isCdExtra = true, shortReadSession1 = shortReadSession1)
        }
    }

    private fun createFullTocData(track1Lba: Int, isCdExtra: Boolean): ByteArray {
        val tocData = ByteArray(2048)
        val dataBuffer = ByteBuffer.wrap(tocData).order(ByteOrder.BIG_ENDIAN)

        // For Full TOC, header is 4 bytes.
        // We'll add 1 track + 0xA2 point
        val entryCount = 2
        val tocDataLength = 2 + (entryCount * 11)
        dataBuffer.putShort(tocDataLength.toShort())
        dataBuffer.put(1.toByte()) // First session
        dataBuffer.put(if (isCdExtra) 2.toByte() else 1.toByte()) // Last session

        dataBuffer.position(4)
        // Track 1 descriptor
        dataBuffer.put(1) // SESSION 1
        dataBuffer.put(0x10) // ADR/CONTROL (Audio)
        dataBuffer.put(0) // TNO
        dataBuffer.put(1) // POINT (Track 1)
        dataBuffer.put(0) // MIN
        dataBuffer.put(0) // SEC
        dataBuffer.put(0) // FRAME
        dataBuffer.put(0) // ZERO
        dataBuffer.put(0) // PMIN
        dataBuffer.put(0) // PSEC
        dataBuffer.put(0) // PFRAME (Wait, track 1 PMIN/PSEC/PFRAME should be MSF, but we just need POINT 0xA2)

        // Session 1 Lead-out (0xA2)
        dataBuffer.put(1) // SESSION 1
        dataBuffer.put(0x10) // ADR/CONTROL
        dataBuffer.put(0) // TNO
        dataBuffer.put(0xA2.toByte()) // POINT
        dataBuffer.put(0) // MIN
        dataBuffer.put(0) // SEC
        dataBuffer.put(0) // FRAME
        dataBuffer.put(0) // ZERO

        // Convert track1Lba + 10000 to MSF
        val audioLeadOutLba = track1Lba + 10000
        val pmin = audioLeadOutLba / 75 / 60
        val psec = (audioLeadOutLba / 75) % 60
        val pframe = audioLeadOutLba % 75

        dataBuffer.put(pmin.toByte()) // PMIN
        dataBuffer.put(psec.toByte()) // PSEC
        dataBuffer.put(pframe.toByte()) // PFRAME

        return tocData
    }

    private fun createFakeTocData(track1Lba: Int, isCdExtra: Boolean = false): ByteArray {
        val tocData = ByteArray(804)
        val dataBuffer = ByteBuffer.wrap(tocData).order(ByteOrder.BIG_ENDIAN)

        // 3 tracks + 1 lead-out
        val entryCount = if (isCdExtra) 5 else 4
        val tocDataLength = 2 + (entryCount * 8)
        dataBuffer.putShort(tocDataLength.toShort())
        dataBuffer.put(1.toByte()) // first track
        dataBuffer.put(if (isCdExtra) 4.toByte() else 3.toByte()) // last track

        val lbas = listOf(
            track1Lba,
            track1Lba + 10000,
            track1Lba + 20000
        )
        val dataTrackLba = track1Lba + 30000
        val leadOutLba = if (isCdExtra) track1Lba + 40000 else track1Lba + 30000

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

        if (isCdExtra) {
            // Data Track (Track 4)
            dataBuffer.put(0)
            dataBuffer.put(0x14) // 0x14 indicates data track (0x04 bit set)
            dataBuffer.put(4)
            dataBuffer.put(0)
            dataBuffer.putInt(dataTrackLba)
        }

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

        val result = readTocCommand.execute()

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

        val result = readTocCommand.execute()

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

        val result = readTocCommand.execute()

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

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `test CD-Extra heuristic produces correct audioLeadOutLba`() {
        // Simulate a drive that fails both Full TOC and MSF Session 1 reads,
        // forcing the heuristic path. track1Lba=0 gives pregapOffset=150.
        // We push the data track out to 40000 so the heuristic leadout is
        // greater than the last audio track (20150), passing validation.
        // dataTrackLba (raw) = 40000, normalised = 40150.
        // Expected heuristic: 40150 - 11400 = 28750.
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int
            when (length) {
                31 -> length  // CBW
                804 -> {
                    val fakeData = createFakeTocData(0, isCdExtra = true)
                    val bb = ByteBuffer.wrap(fakeData).order(ByteOrder.BIG_ENDIAN)
                    bb.putInt(32, 40000) // data track LBA
                    bb.putInt(40, 50000) // physical lead-out LBA
                    System.arraycopy(fakeData, 0, buffer, 0, fakeData.size)
                    fakeData.size
                }
                13 -> {
                    val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                    cswBuffer.putInt(0x53425355)
                    cswBuffer.putInt(3)
                    cswBuffer.putInt(0)
                    cswBuffer.put(0.toByte())
                    length
                }
                else -> -1  // fail Full TOC and MSF Session 1 (length==2048)
            }
        }
        `when`(transport.bulkTransferFully(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenReturn(-1)  // fail all bulkTransferFully calls (used for 2048-byte reads)

        val result = readTocCommand.execute()

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.tracks.size)
        assertEquals(28750, toc.audioLeadOutLba)  // 40150 - 11400
        assertEquals(50150, toc.leadOutLba)       // physical lead-out remains unchanged
    }

    @Test
    fun `test CD-Extra session 1 short read`() {
        // Simulate a CD-Extra disc with a short read on the Session 1 TOC
        setupMockTransferWithCdExtra(150, shortReadSession1 = true)

        val result = readTocCommand.execute()

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.tracks.size) // The 3 audio tracks

        // The short read returns 12 bytes. That's enough for the header (4 bytes) and Track 1 (8 bytes),
        // but it misses the Session 1 Lead-Out (AA). It shouldn't crash, and should fall back gracefully.
        assertEquals(150, toc.tracks[0].lba)
        assertEquals(10150, toc.tracks[1].lba)
        assertEquals(20150, toc.tracks[2].lba)
        // With main TOC leadout canonical parsing, we should have leadOutLba = track1Lba + 40000 = 40150
        assertEquals(40150, toc.leadOutLba)
    }

    @Test
    fun `test malformed TOC lengths and alignment`() {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int

            if (length == 31) {
                return@thenAnswer length
            } else if (length == 804) {
                // Return misaligned length
                buffer[0] = 0
                buffer[1] = 5 // length 5, so 5 - 2 = 3 which is not % 8 == 0
                return@thenAnswer 804
            } else if (length == 13) {
                val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
                cswBuffer.putInt(3) // tag
                cswBuffer.putInt(0) // data residue
                cswBuffer.put(0.toByte()) // status success
                return@thenAnswer length
            } else {
                return@thenAnswer -1
            }
        }

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `test short USB reads`() {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int

            if (length == 31) {
                return@thenAnswer length
            } else if (length == 804) {
                // Short read of 2 bytes
                buffer[0] = 0
                buffer[1] = 10
                return@thenAnswer 2
            } else if (length == 13) {
                val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
                cswBuffer.putInt(3) // tag
                cswBuffer.putInt(0) // data residue
                cswBuffer.put(0.toByte()) // status success
                return@thenAnswer length
            } else {
                return@thenAnswer -1
            }
        }

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `test missing leadout`() {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int

            if (length == 31) {
                return@thenAnswer length
            } else if (length == 804) {
                val dataBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
                dataBuffer.putShort(18) // 2 entries -> 2 + 16 = 18
                dataBuffer.put(1)
                dataBuffer.put(2)
                dataBuffer.position(4)
                // Track 1
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(1)
                dataBuffer.put(0)
                dataBuffer.putInt(150)
                // Track 2
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(2)
                dataBuffer.put(0)
                dataBuffer.putInt(200)
                return@thenAnswer 804
            } else if (length == 13) {
                val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
                cswBuffer.putInt(3) // tag
                cswBuffer.putInt(0) // data residue
                cswBuffer.put(0.toByte()) // status success
                return@thenAnswer length
            } else {
                return@thenAnswer -1
            }
        }

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `test duplicate tracks`() {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int

            if (length == 31) {
                return@thenAnswer length
            } else if (length == 804) {
                val dataBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
                dataBuffer.putShort(26) // 3 entries -> 2 + 24 = 26
                dataBuffer.put(1)
                dataBuffer.put(2)
                dataBuffer.position(4)
                // Track 1
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(1)
                dataBuffer.put(0)
                dataBuffer.putInt(150)
                // Duplicate Track 1
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(1)
                dataBuffer.put(0)
                dataBuffer.putInt(200)
                // Lead-out
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(0xAA.toByte())
                dataBuffer.put(0)
                dataBuffer.putInt(300)
                return@thenAnswer 804
            } else if (length == 13) {
                val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
                cswBuffer.putInt(3) // tag
                cswBuffer.putInt(0) // data residue
                cswBuffer.put(0.toByte()) // status success
                return@thenAnswer length
            } else {
                return@thenAnswer -1
            }
        }

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `test descending LBAs`() {
        `when`(transport.bulkTransfer(
            any(UsbEndpoint::class.java) ?: inEndpoint,
            any(ByteArray::class.java) ?: ByteArray(0),
            anyInt(),
            anyInt()
        )).thenAnswer { invocation ->
            val buffer = invocation.arguments[1] as ByteArray
            val length = invocation.arguments[2] as Int

            if (length == 31) {
                return@thenAnswer length
            } else if (length == 804) {
                val dataBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
                dataBuffer.putShort(26) // 3 entries -> 2 + 24 = 26
                dataBuffer.put(1)
                dataBuffer.put(2)
                dataBuffer.position(4)
                // Track 1
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(1)
                dataBuffer.put(0)
                dataBuffer.putInt(200)
                // Track 2 with smaller LBA
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(2)
                dataBuffer.put(0)
                dataBuffer.putInt(150)
                // Lead-out
                dataBuffer.put(0)
                dataBuffer.put(0x10)
                dataBuffer.put(0xAA.toByte())
                dataBuffer.put(0)
                dataBuffer.putInt(300)
                return@thenAnswer 804
            } else if (length == 13) {
                val cswBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                cswBuffer.putInt(0x53425355) // CSW_SIGNATURE
                cswBuffer.putInt(3) // tag
                cswBuffer.putInt(0) // data residue
                cswBuffer.put(0.toByte()) // status success
                return@thenAnswer length
            } else {
                return@thenAnswer -1
            }
        }

        val result = readTocCommand.execute()
        org.junit.Assert.assertNull(result)
    }
}
