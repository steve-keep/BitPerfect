package com.bitperfect.core.engine

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RippingIntegrationTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun testVirtualDriveToTocReaderFlow() = runBlocking {
        val testCd = TestCd(
            artist = "Test Artist",
            album = "Test Album",
            tracks = listOf("Track 1", "Track 2")
        )
        val virtualDriver = VirtualScsiDriver(testCd)
        val tocReader = TocReader(virtualDriver)

        val toc = tocReader.readToc(0, 0, 0)

        assertNotNull(toc)
        assertEquals(1, toc!!.firstTrack)
        assertEquals(2, toc.lastTrack)
        assertEquals(2, toc.tracks.size)

        // Verify MSF to LBA and back works
        // TestCd default offsets:
        // Track 1: 150
        // Track 2: 150 + 15000 + 100 = 15250
        // Lead-out: 15250 + 15000 + 200 = 30450

        // msfToLba in TocReader subtracts 150.
        // VirtualScsiDriver converts LBA to MSF adding 150.
        // So LBA 150 in VirtualScsiDriver becomes 00:04:00 MSF? No.
        // LBA 0 is 00:02:00.
        // LBA 150 is 00:04:00? (0*4500 + 4*75 + 0) - 150 = 300 - 150 = 150. Yes.

        assertEquals(150, toc.tracks[0].startLba)
        assertEquals(15250, toc.tracks[1].startLba)
        assertEquals(30450, toc.leadOutLba)

        assertEquals(15100, toc.tracks[0].durationSectors)
        assertEquals(30450 - 15250, toc.tracks[1].durationSectors)
    }

    @Test
    fun testRippingEngineWithVirtualDrive() = runBlocking {
        val testCd = TestCd(
            artist = "Test Artist",
            album = "Test Album",
            tracks = listOf("Track 1")
        )
        val virtualDriver = VirtualScsiDriver(testCd)
        val rippingEngine = RippingEngine(virtualDriver, mockk(relaxed = true))

        val capabilities = DriveCapabilities(hasCache = false, supportsC2 = false)

        rippingEngine.startSecureRip(context, 0, "dummy.flac", capabilities, virtualDriver)

        val state = rippingEngine.ripState.value
        assertEquals("Secure Rip Complete", state.status)
        assertEquals(0, state.errorCount)
        assertEquals(50, state.totalSectors) // We capped it at 50 in startSecureRip for testing
    }
}
