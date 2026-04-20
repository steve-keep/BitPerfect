package com.bitperfect.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VirtualScsiDriverTest {

    private val testCd = TestCd(
        artist = "Test Artist",
        album = "Test Album",
        tracks = listOf("Track 1", "Track 2")
    )
    private val driver = VirtualScsiDriver(testCd)

    @Test
    fun testInquiry() {
        val command = byteArrayOf(0x12, 0, 0, 0, 36, 0)
        val response = driver.executeScsiCommand(1, command, 36)

        assertTrue(response != null)
        assertEquals(36, response!!.size)
        assertEquals(0x05.toByte(), response[0]) // CD-ROM
        assertEquals("ASUS", String(response.sliceArray(8 until 16)).trim())
        assertEquals("DRW-24B1ST   a", String(response.sliceArray(16 until 32)).trim())
    }

    @Test
    fun testGetConfiguration() {
        val command = byteArrayOf(0x46, 0x02, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0)
        val response = driver.executeScsiCommand(1, command, 256)

        assertTrue(response != null)
        assertTrue(response!!.size >= 24)

        // CD Read feature (0x0107)
        assertEquals(0x01.toByte(), response[8])
        assertEquals(0x07.toByte(), response[9])
        assertEquals(0x02.toByte(), response[12]) // AccurateStream

        // C2 feature (0x0014)
        assertEquals(0x00.toByte(), response[16])
        assertEquals(0x14.toByte(), response[17])
        assertEquals(0x01.toByte(), response[20]) // C2 support
    }

    @Test
    fun testCacheTiming() {
        val lba = 100
        val command = byteArrayOf(
            0xBE.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),
            0, 0, 1, 0x10, 0
        )

        // First read (cache miss)
        val start1 = System.currentTimeMillis()
        driver.executeScsiCommand(1, command, 2352)
        val rtt1 = System.currentTimeMillis() - start1
        assertTrue("First read should be slow (>5ms)", rtt1 >= 5)

        // Second read (cache hit)
        val start2 = System.currentTimeMillis()
        driver.executeScsiCommand(1, command, 2352)
        val rtt2 = System.currentTimeMillis() - start2
        assertTrue("Second read should be fast (<5ms)", rtt2 < 5)
    }

    @Test
    fun testModeSense10() {
        val command = byteArrayOf(0x5A, 0, 0x2A, 0, 0, 0, 0, 0, 30, 0)
        val response = driver.executeScsiCommand(1, command, 30)

        assertTrue(response != null)
        assertTrue(response!!.size >= 11)
        assertEquals(0x01.toByte(), response[10]) // C2 Supported
    }

    @Test
    fun testReadToc() {
        val command = byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, 0, 20, 0)
        val response = driver.executeScsiCommand(1, command, 20)

        assertTrue(response != null)
        assertEquals(1.toByte(), response!![2]) // First track
        assertEquals(2.toByte(), response!![3]) // Last track

        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        assertEquals(testCd.trackOffsets[1] - 150, buffer.getInt(4 + 4))
    }

    @Test
    fun testReadCd() {
        val lba = 100
        val command = byteArrayOf(
            0xBE.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),
            0, 0, 1, 0x10, 0
        )
        val response = driver.executeScsiCommand(1, command, 2352)

        assertTrue(response != null)
        assertEquals(2352, response!!.size)
        assertEquals(((lba + 0) % 256).toByte(), response[0])
        assertEquals(((lba + 1) % 256).toByte(), response[1])
    }
}
