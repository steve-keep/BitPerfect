package com.bitperfect.core.engine

import io.mockk.coEvery
import io.mockk.mockk
import com.bitperfect.driver.IScsiDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriveCapabilityDetectorTest {

    @Test
    fun `test detect capability features`() = runBlocking {
        val mockScsiDriver = mockk<IScsiDriver>()
        val mockDriveOffsetService = mockk<DriveOffsetService>()

        // Mock Inquiry
        val inquiryResponse = ByteArray(36)
        inquiryResponse[0] = 0x05
        "TestVend".toByteArray().copyInto(inquiryResponse, 8)
        "TestProduct     ".toByteArray().copyInto(inquiryResponse, 16)
        "1.00".toByteArray().copyInto(inquiryResponse, 32)
        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0x12.toByte() }, 36, any(), any()) } returns inquiryResponse

        // Mock Get Configuration (0x46)
        val getConfigResponse = ByteArray(256)
        getConfigResponse[3] = 24 // Data length

        // CD Read Feature (0x0107) -> AccurateStream = true
        getConfigResponse[8] = 0x01
        getConfigResponse[9] = 0x07
        getConfigResponse[11] = 4
        getConfigResponse[12] = 0x02

        // C2 Feature (0x0014) -> C2 Support = true
        getConfigResponse[16] = 0x00
        getConfigResponse[17] = 0x14
        getConfigResponse[19] = 4
        getConfigResponse[20] = 0x01

        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0x46.toByte() }, 256, any(), any()) } returns getConfigResponse

        // Mock DriveOffsetService
        coEvery { mockDriveOffsetService.findOffsetForDrive(any(), any()) } returns 123

        // Mock Read CD for cache check (no cache scenario)
        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0xBE.toByte() }, 2352, any(), any()) } answers {
            Thread.sleep(10) // Simulate slow read (cache miss)
            ByteArray(2352)
        }

        val detector = DriveCapabilityDetector(mockScsiDriver, mockDriveOffsetService)
        val result = detector.detect(1, 0, 0)

        assertTrue(result.isSuccess)
        val capabilities = result.getOrNull()!!
        assertEquals("TestVend", capabilities.vendor)
        assertEquals("TestProduct", capabilities.product)
        assertTrue(capabilities.accurateStream)
        assertTrue(capabilities.supportsC2)
        assertEquals(123, capabilities.readOffset)
        assertTrue(capabilities.offsetFromAccurateRip)
        assertFalse(capabilities.hasCache) // Because we mocked a 10ms delay
    }

    @Test
    fun `test cache timing detection`() = runBlocking {
        val mockScsiDriver = mockk<IScsiDriver>()
        val mockDriveOffsetService = mockk<DriveOffsetService>()

        // Mock Inquiry
        val inquiryResponse = ByteArray(36)
        "TestVend".toByteArray().copyInto(inquiryResponse, 8)
        "TestProduct     ".toByteArray().copyInto(inquiryResponse, 16)
        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0x12.toByte() }, 36, any(), any()) } returns inquiryResponse

        // Mock Get Configuration (no features)
        val getConfigResponse = ByteArray(256)
        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0x46.toByte() }, 256, any(), any()) } returns getConfigResponse

        coEvery { mockDriveOffsetService.findOffsetForDrive(any(), any()) } returns null

        // Mock Read CD for cache check (simulate cache hit for same sector, miss for different)
        var lastSector = -1
        coEvery { mockScsiDriver.executeScsiCommand(1, match { it[0] == 0xBE.toByte() }, 2352, any(), any()) } answers {
            val cmd = it.invocation.args[1] as ByteArray
            val lba = ((cmd[2].toInt() and 0xFF) shl 24) or
                      ((cmd[3].toInt() and 0xFF) shl 16) or
                      ((cmd[4].toInt() and 0xFF) shl 8) or
                      (cmd[5].toInt() and 0xFF)

            if (lba != lastSector) {
                Thread.sleep(10) // Cache miss
            }
            lastSector = lba
            ByteArray(2352)
        }

        val detector = DriveCapabilityDetector(mockScsiDriver, mockDriveOffsetService)
        val result = detector.detect(1, 0, 0)

        assertTrue(result.isSuccess)
        val capabilities = result.getOrNull()!!
        assertTrue(capabilities.hasCache)
        assertTrue(capabilities.cacheSizeKb > 0)
    }
}
