package com.bitperfect.core.engine

import com.bitperfect.driver.IScsiDriver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CdSectorReaderTest {

    private val scsiDriver = mockk<IScsiDriver>()
    private val fd = 1
    private val endpointIn = 0x81
    private val endpointOut = 0x01
    private val reader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)

    @Test
    fun testReadSectors_BasicReadCd() {
        val lba = 100L
        val sectorCount = 2
        val expectedLength = 2352 * 2
        val responseData = ByteArray(expectedLength) { it.toByte() }

        every {
            scsiDriver.executeScsiCommand(fd, match {
                it[0] == 0xBE.toByte() && it[9] == 0x10.toByte()
            }, expectedLength, endpointIn, endpointOut)
        } returns responseData

        val result = reader.readSectors(lba, sectorCount, includeC2 = false, includeSubchannel = false)

        assertArrayEquals(responseData, result)
        verify {
            scsiDriver.executeScsiCommand(fd, match {
                it[0] == 0xBE.toByte() &&
                it[5] == (lba and 0xFF).toByte() &&
                it[8] == sectorCount.toByte() &&
                it[9] == 0x10.toByte() &&
                it[10] == 0x00.toByte()
            }, expectedLength, endpointIn, endpointOut)
        }
    }

    @Test
    fun testReadSectors_WithC2AndSubchannel() {
        val lba = 100L
        val sectorCount = 1
        val bytesPerSector = 2352 + 294 + 96
        val expectedLength = bytesPerSector * sectorCount
        val responseData = ByteArray(expectedLength)

        every {
            scsiDriver.executeScsiCommand(fd, any(), expectedLength, endpointIn, endpointOut)
        } returns responseData

        val result = reader.readSectors(lba, sectorCount, includeC2 = true, includeSubchannel = true)

        assertArrayEquals(responseData, result)
        verify {
            scsiDriver.executeScsiCommand(fd, match {
                it[0] == 0xBE.toByte() &&
                it[9] == 0x12.toByte() && // 0x10 | 0x02
                it[10] == 0x01.toByte() // Subchannel
            }, expectedLength, endpointIn, endpointOut)
        }
    }

    @Test
    fun testReadSectors_FallbackToRead10() {
        val lba = 100L
        val sectorCount = 2
        val expectedLength = 2352 * sectorCount

        // READ CD fails
        every {
            scsiDriver.executeScsiCommand(fd, match { it[0] == 0xBE.toByte() }, expectedLength, endpointIn, endpointOut)
        } returns null

        val fallbackData = ByteArray(expectedLength) { 0xAA.toByte() }

        // READ(10) succeeds
        every {
            scsiDriver.executeScsiCommand(fd, match { it[0] == 0x28.toByte() }, expectedLength, endpointIn, endpointOut)
        } returns fallbackData

        val result = reader.readSectors(lba, sectorCount, includeC2 = false, includeSubchannel = false)

        assertArrayEquals(fallbackData, result)
        verify {
            scsiDriver.executeScsiCommand(fd, match {
                it[0] == 0x28.toByte() &&
                it[5] == (lba and 0xFF).toByte() &&
                it[8] == sectorCount.toByte()
            }, expectedLength, endpointIn, endpointOut)
        }
    }

    @Test
    fun testReadSectors_FallbackToRead10_WithPaddedC2() {
        val lba = 100L
        val sectorCount = 1
        val expectedLength = (2352 + 294) * sectorCount // includeC2 = true

        // READ CD fails
        every {
            scsiDriver.executeScsiCommand(fd, match { it[0] == 0xBE.toByte() }, expectedLength, endpointIn, endpointOut)
        } returns null

        val read10Length = 2352 * sectorCount
        val fallbackData = ByteArray(read10Length) { 0xBB.toByte() }

        // READ(10) succeeds
        every {
            scsiDriver.executeScsiCommand(fd, match { it[0] == 0x28.toByte() }, read10Length, endpointIn, endpointOut)
        } returns fallbackData

        val result = reader.readSectors(lba, sectorCount, includeC2 = true, includeSubchannel = false)

        assertEquals(expectedLength, result?.size)
        // Check user data is copied
        for (i in 0 until 2352) {
            assertEquals(0xBB.toByte(), result!![i])
        }
        // Check padded C2 is zeros
        for (i in 2352 until expectedLength) {
            assertEquals(0.toByte(), result!![i])
        }
    }
}
