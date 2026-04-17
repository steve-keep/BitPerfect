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
    private val context = mockk<Context>(relaxed = true)
    private lateinit var rippingEngine: RippingEngine

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        rippingEngine = RippingEngine(scsiDriver, flacEncoder)
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
}
