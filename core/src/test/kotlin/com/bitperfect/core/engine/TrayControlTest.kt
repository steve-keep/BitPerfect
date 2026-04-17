package com.bitperfect.core.engine

import com.bitperfect.driver.IScsiDriver
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class TrayControlTest {

    private val scsiDriver = mockk<IScsiDriver>()
    private lateinit var rippingEngine: RippingEngine

    @Before
    fun setUp() {
        rippingEngine = RippingEngine(scsiDriver)
    }

    @Test
    fun testEjectDisc_SendsCorrectCommand() = runBlocking {
        val fd = 1

        // Mock successful eject command (0x1B)
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x1B.toByte() }, any(), any(), any(), any()) } returns ByteArray(0)
        // Mock TUR (0x00) to return null (Tray Open) after eject
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x00.toByte() }, any(), any(), any(), any()) } returns null
        // Mock REQUEST SENSE (0x03) for No Disc / Tray Open
        val senseData = ByteArray(18)
        senseData[2] = 0x02 // NOT READY
        senseData[12] = 0x3A // MEDIUM NOT PRESENT
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x03.toByte() }, any(), any(), any(), any()) } returns senseData

        rippingEngine.ejectDisc(fd, scsiDriver)

        verify { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x1B.toByte() }, 0, any(), any(), any()) }
        assertEquals("No Disc / Tray Open", rippingEngine.ripState.value.driveStatus)
        assertFalse(rippingEngine.ripState.value.isTrayOperationInProgress)
    }

    @Test
    fun testLoadTray_SendsCorrectCommand() = runBlocking {
        val fd = 1

        // Mock successful load command (0x1B)
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x1B.toByte() }, any(), any(), any(), any()) } returns ByteArray(0)
        // Mock TUR (0x00) to return success after load
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x00.toByte() }, any(), any(), any(), any()) } returns ByteArray(0)
        // Mock TOC response (needed because pollDriveStatus calls it)
        val tocResponse = ByteArray(804)
        tocResponse[1] = 0x02
        tocResponse[2] = 1
        tocResponse[3] = 1
        every { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x43.toByte() }, any(), any(), any(), any()) } returns tocResponse

        rippingEngine.loadTray(fd, scsiDriver)

        verify { scsiDriver.executeScsiCommand(fd, match { it[0] == 0x1B.toByte() }, 0, any(), any(), any()) }
        assertEquals("Ready", rippingEngine.ripState.value.driveStatus)
        assertFalse(rippingEngine.ripState.value.isTrayOperationInProgress)
    }

    @Test
    fun testVirtualDriver_TraySimulation() = runBlocking {
        val testCd = TestCd("Artist", "Album", listOf("Track 1"))
        val virtualDriver = VirtualScsiDriver(testCd)
        val fd = 999

        // 1. Initial state (Closed)
        val turResp1 = virtualDriver.executeScsiCommand(fd, byteArrayOf(0, 0, 0, 0, 0, 0), 0, 0, 0, 0)
        assertTrue(turResp1 != null)

        // 2. Eject
        virtualDriver.executeScsiCommand(fd, byteArrayOf(0x1B, 0, 0, 0, 0x02, 0), 0, 0, 0, 0)
        val turResp2 = virtualDriver.executeScsiCommand(fd, byteArrayOf(0, 0, 0, 0, 0, 0), 0, 0, 0, 0)
        assertTrue(turResp2 == null)

        // 3. Request Sense when Ejected
        val senseResp = virtualDriver.executeScsiCommand(fd, byteArrayOf(0x03, 0, 0, 0, 18, 0), 18, 0, 0, 0)
        assertTrue(senseResp != null)
        assertEquals(0x02.toByte(), senseResp!![2]) // NOT READY
        assertEquals(0x3A.toByte(), senseResp[12]) // MEDIUM NOT PRESENT

        // 4. Load
        virtualDriver.executeScsiCommand(fd, byteArrayOf(0x1B, 0, 0, 0, 0x03, 0), 0, 0, 0, 0)
        val turResp3 = virtualDriver.executeScsiCommand(fd, byteArrayOf(0, 0, 0, 0, 0, 0), 0, 0, 0, 0)
        assertTrue(turResp3 != null)
    }
}
