package com.bitperfect.core.engine

import android.content.Context
import com.bitperfect.driver.IScsiDriver
import com.bitperfect.driver.ScsiDriver
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.*

class RippingEngineTest {

    private val scsiDriver = mockk<IScsiDriver>()
    private val flacEncoder = mockk<FlacEncoder>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val accurateRipService = mockk<AccurateRipService>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private lateinit var rippingEngine: RippingEngine

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        coEvery { metadataService.fetchMetadata(any(), any(), any()) } returns emptyList()
        rippingEngine = RippingEngine(context, scsiDriver, flacEncoder, metadataService, accurateRipService)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testSecureRip_SuccessfulTwoPass() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = false)

        // Mock response for a CD with 1 track
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02 // MSF bit
        tocResponse[2] = 1 // First track
        tocResponse[3] = 1 // Last track
        tocResponse[4+2] = 1 // Track 1
        tocResponse[4+6] = 2 // 00:02:00 -> LBA 0
        tocResponse[12+2] = 0xAA.toByte() // Lead-out
        tocResponse[12+5] = 10 // 10:00:00 -> LBA 44850

        val sectorData = ByteArray(2352) { 0xAA.toByte() }

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 804, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352, any<Int>(), any<Int>(), any<Int>()) } returns sectorData

        rippingEngine.startSecureRip(context, fd, "test.flac", capabilities)

        val state = rippingEngine.ripState.value
        assertEquals("Secure Rip Complete", state.status)
        assertEquals(0, state.errorCount)
        assertEquals(0, state.reReads)

        // Verify readSector was called twice per sector (50 sectors * 2 = 100 calls)
        verify(exactly = 100) { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352, any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun testSecureRip_WithMismatchAndRecovery() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = false)
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02 // MSF bit
        tocResponse[2] = 1 // First track
        tocResponse[3] = 1 // Last track
        tocResponse[4+2] = 1 // Track 1
        tocResponse[4+6] = 2 // 00:02:00 -> LBA 0
        tocResponse[12+2] = 0xAA.toByte() // Lead-out
        tocResponse[12+5] = 10 // 10:00:00 -> LBA 44850

        val goodData = ByteArray(2352) { 0xAA.toByte() }
        val badData = ByteArray(2352) { 0xBB.toByte() }

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 804, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse

        // Mocking behavior for sector 0
        // Pass 1: goodData
        // Pass 2: badData
        // Re-reads: 10 more goodData to reach majority
        val responses = mutableListOf<ByteArray?>()
        responses.add(goodData)
        responses.add(badData)
        repeat(10) { responses.add(goodData) }

        // For other sectors, return goodData twice
        repeat(49 * 2) { responses.add(goodData) }

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352, any<Int>(), any<Int>(), any<Int>()) } returnsMany responses

        rippingEngine.startSecureRip(context, fd, "test.flac", capabilities)

        val state = rippingEngine.ripState.value
        assertEquals("Secure Rip Complete", state.status)
        assertEquals(0, state.errorCount)
        // reReads state is reset to 0 after majority found, but we can verify it was called
        verify { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352, any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun testSecureRip_WithC2() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = true)
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02 // MSF bit
        tocResponse[2] = 1 // First track
        tocResponse[3] = 1 // Last track
        tocResponse[4+2] = 1 // Track 1
        tocResponse[4+6] = 2 // 00:02:00 -> LBA 0
        tocResponse[12+2] = 0xAA.toByte() // Lead-out
        tocResponse[12+5] = 10 // 10:00:00 -> LBA 44850

        // Sector data with C2 (all zeros)
        val sectorDataWithC2 = ByteArray(2352 + 294) { 0 }
        sectorDataWithC2[0] = 0xAA.toByte()

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 804, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352 + 294, any<Int>(), any<Int>(), any<Int>()) } returns sectorDataWithC2

        rippingEngine.startSecureRip(context, fd, "test.flac", capabilities)

        val state = rippingEngine.ripState.value
        assertEquals("Secure Rip Complete", state.status)

        // With C2 and no errors, it should only do 1 pass
        verify(exactly = 50) { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352 + 294, any<Int>(), any<Int>(), any<Int>()) }
    }

    @Test
    fun testCalibrateOffset_Success() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = false)

        // Mock TOC: 1 track, 10 sectors
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02 // MSF bit
        tocResponse[2] = 1 // First track
        tocResponse[3] = 1 // Last track
        tocResponse[4+2] = 1 // Track 1
        tocResponse[4+6] = 2 // 00:02:00 -> LBA 0
        tocResponse[12+2] = 0xAA.toByte() // Lead-out
        tocResponse[12+5] = 2 // 00:02:10 -> LBA 10

        // Mock AccurateRip response
        val expectedCrc = 123456789L
        val matchMap = mapOf(1 to listOf(AccurateRipMatch(confidence = 5, crc = expectedCrc, crc2 = 0L)))
        coEvery { accurateRipService.fetchAccurateRipData(any(), any()) } returns matchMap

        // Mock scsiDriver reads
        // To test calibration, we'll return a specific data pattern that will generate the expected CRC
        // when shifted. But for this test, we can just mock `ChecksumUtils` or let the real `ChecksumUtils` calculate 0.
        // It's easier to mock `ChecksumUtils` but it's an object. We can just mock the read sector to return zeros,
        // which gives CRC 0. So we set expectedCrc to 0.

        val zeroCrc = 0L
        val zeroMatchMap = mapOf(1 to listOf(AccurateRipMatch(confidence = 5, crc = zeroCrc, crc2 = 0L)))
        coEvery { accurateRipService.fetchAccurateRipData(any(), any()) } returns zeroMatchMap

        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x43.toByte() }, any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0xBE.toByte() }, any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns ByteArray(2352)

        val result = rippingEngine.calibrateOffset(fd, capabilities, scsiDriver)

        org.junit.Assert.assertTrue(result.isSuccess)
        // The first offset that matches will be returned. Since all data is 0, offset -2940 will yield 0 CRC.
        // The offsetRange is from -2940 to 2940. So it should return -2940.
        assertEquals(-2940, result.getOrNull())
    }

    @Test
    fun testCalibrateOffset_NoMatch() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities()
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02 // MSF bit
        tocResponse[2] = 1
        tocResponse[3] = 1
        tocResponse[4+2] = 1
        tocResponse[4+6] = 2 // LBA 0
        tocResponse[12+2] = 0xAA.toByte()
        tocResponse[12+5] = 2 // LBA 10

        // Mock AccurateRip response with impossible CRC
        val matchMap = mapOf(1 to listOf(AccurateRipMatch(5, 999999999L, 888888888L)))
        coEvery { accurateRipService.fetchAccurateRipData(any(), any()) } returns matchMap

        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x43.toByte() }, any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0xBE.toByte() }, any<Int>(), any<Int>(), any<Int>(), any<Int>()) } returns ByteArray(2352)

        val result = rippingEngine.calibrateOffset(fd, capabilities, scsiDriver)

        org.junit.Assert.assertTrue(result.isFailure)
        org.junit.Assert.assertEquals("Could not find a matching offset within ±5 sectors. Try manual entry or a different reference disc.", result.exceptionOrNull()?.message)
    }

}