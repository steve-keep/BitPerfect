package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import com.bitperfect.core.models.DiscToc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer

class ReadTocCommandTest {

    @Test
    fun `execute parses TOC successfully`() {
        val outEndpoint = mockEndpoint()
        val inEndpoint = mockEndpoint()

        val tocData = ByteArray(804)
        tocData[0] = 0x00
        tocData[1] = 0x1A
        tocData[2] = 0x01
        tocData[3] = 0x02

        tocData[4] = 0x00
        tocData[5] = 0x10
        tocData[6] = 0x01
        tocData[7] = 0x00
        tocData[8] = 0x00
        tocData[9] = 0x00
        tocData[10] = 0x02
        tocData[11] = 0x00

        tocData[12] = 0x00
        tocData[13] = 0x14
        tocData[14] = 0x02
        tocData[15] = 0x00
        tocData[16] = 0x00
        tocData[17] = 0x05
        tocData[18] = 0x00
        tocData[19] = 0x00

        tocData[20] = 0x00
        tocData[21] = 0x10
        tocData[22] = 0xAA.toByte()
        tocData[23] = 0x00
        tocData[24] = 0x00
        tocData[25] = 10
        tocData[26] = 0
        tocData[27] = 0

        val csw = ByteArray(13)
        val cswBuffer = ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        cswBuffer.putInt(0x53425355)
        cswBuffer.putInt(3)
        cswBuffer.putInt(0)
        cswBuffer.put(0)

        val transport = object : UsbTransport {
            override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
                if (length == 31) {
                    return 31
                } else if (length == 804) {
                    System.arraycopy(tocData, 0, buffer, 0, tocData.size)
                    return 804
                } else if (length == 13) {
                    System.arraycopy(csw, 0, buffer, 0, csw.size)
                    return 13
                }
                return -1
            }
        }

        val command = ReadTocCommand(transport, outEndpoint, inEndpoint)
        val toc = command.execute()

        assertNotNull(toc)
        assertEquals(1, toc!!.firstTrack)
        assertEquals(2, toc.lastTrack)
        assertEquals(2, toc.tracks.size)

        val t1 = toc.tracks[0]
        assertEquals(1, t1.number)
        assertEquals(0, t1.lba)
        assertTrue(t1.isAudio)

        val t2 = toc.tracks[1]
        assertEquals(2, t2.number)
        assertEquals(22350, t2.lba)
        assertFalse(t2.isAudio)

        assertEquals(44850, toc.leadOutLba)
    }

    private fun mockEndpoint(): UsbEndpoint {
        return org.mockito.Mockito.mock(UsbEndpoint::class.java)
    }
}
