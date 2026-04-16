package com.bitperfect.core.engine

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
    private lateinit var rippingEngine: RippingEngine

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        rippingEngine = RippingEngine(flacEncoder)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testSecureRip_SuccessfulTwoPass() = runBlocking {
        val fd = 1
        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = false)
        val tocResponse = byteArrayOf(0, 18, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val sectorData = ByteArray(2352) { 0xAA.toByte() }

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 18, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352, any<Int>(), any<Int>(), any<Int>()) } returns sectorData

        rippingEngine.startSecureRip(scsiDriver, fd, "test.flac", capabilities)

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
        val tocResponse = byteArrayOf(0, 18, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        val goodData = ByteArray(2352) { 0xAA.toByte() }
        val badData = ByteArray(2352) { 0xBB.toByte() }

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 18, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse

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

        rippingEngine.startSecureRip(scsiDriver, fd, "test.flac", capabilities)

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
        val tocResponse = byteArrayOf(0, 18, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        // Sector data with C2 (all zeros)
        val sectorDataWithC2 = ByteArray(2352 + 294) { 0 }
        sectorDataWithC2[0] = 0xAA.toByte()

        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 18, any<Int>(), any<Int>(), any<Int>()) } returns tocResponse
        every { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352 + 294, any<Int>(), any<Int>(), any<Int>()) } returns sectorDataWithC2

        rippingEngine.startSecureRip(scsiDriver, fd, "test.flac", capabilities)

        val state = rippingEngine.ripState.value
        assertEquals("Secure Rip Complete", state.status)

        // With C2 and no errors, it should only do 1 pass
        verify(exactly = 50) { scsiDriver.executeScsiCommand(fd, any<ByteArray>(), 2352 + 294, any<Int>(), any<Int>(), any<Int>()) }
    }
}
