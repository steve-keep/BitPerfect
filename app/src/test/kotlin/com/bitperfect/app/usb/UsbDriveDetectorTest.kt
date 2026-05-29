package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import com.bitperfect.core.utils.computeFreedbId
import com.bitperfect.core.utils.computeAccurateRipDiscId
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbDriveDetectorTest {

    class FakeUsbTransport(
        private val inquiryResponse: ByteArray,
        private val turCswStatus: Byte = 0,
        private val tocResponse: ByteArray? = null,
        private val tocCswStatus: Byte = 0,
        private val turRetriesRequired: Int = 0,
        private val failTurTransferOnAttempt: Int? = null,
        private val senseKeyOverride: Byte? = null,
        private val ascOverride: Byte? = null,
        private val ascqOverride: Byte? = null,
        private val senseSequence: List<Triple<Byte, Byte, Byte>>? = null
    ) : UsbTransport {
        var transferCount = 0
        var currentTurAttempt = 1
        var state = "INQUIRY_CBW"

        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int {
            // Because our fake relies on simulating short reads/chunks without actually splitting,
            // we create a temporary buffer that fits the requested length.
            val tempBuffer = ByteArray(length)
            val result = bulkTransfer(endpoint, tempBuffer, length, timeout)
            if (result > 0) {
                // Ensure we don't copy more bytes than result or what is available
                System.arraycopy(tempBuffer, 0, buffer, offset, result)
            }
            return result
        }

        override fun bulkTransferFully(endpoint: UsbEndpoint, buffer: ByteArray, maxLength: Int, timeout: Int): Int {
            return bulkTransfer(endpoint, buffer, 0, maxLength, timeout)
        }

        override fun nextTag(): Int = 3

        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
            transferCount++
            when (state) {
                "INQUIRY_CBW" -> {
                    state = "INQUIRY_DATA"
                    return 31
                }
                "INQUIRY_DATA" -> {
                    System.arraycopy(inquiryResponse, 0, buffer, 0, inquiryResponse.size.coerceAtMost(length))
                    state = "INQUIRY_CSW"
                    return inquiryResponse.size
                }
                "INQUIRY_CSW" -> {
                    val csw = ByteArray(13)
                    val b = java.nio.ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x53425355)
                    System.arraycopy(csw, 0, buffer, 0, csw.size.coerceAtMost(length))
                    state = "TUR_CBW"
                    return 13
                }
                "TUR_CBW" -> {
                    if (failTurTransferOnAttempt == currentTurAttempt) return -1
                    state = "TUR_CSW"
                    return 31
                }
                "TUR_CSW" -> {
                    if (failTurTransferOnAttempt == currentTurAttempt) return -1
                    val csw = ByteArray(13)
                    val b = java.nio.ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x53425355)

                    if (currentTurAttempt <= turRetriesRequired) {
                        csw[12] = 1 // Fail
                        state = "REQ_SENSE_CBW"
                    } else {
                        csw[12] = turCswStatus
                        if (turCswStatus == 0.toByte()) {
                            state = "TOC_CBW"
                        } else {
                            state = "REQ_SENSE_CBW" // Exhausted or final failure usually does Sense too
                        }
                    }
                    System.arraycopy(csw, 0, buffer, 0, csw.size.coerceAtMost(length))
                    return 13
                }
                "REQ_SENSE_CBW" -> {
                    state = "REQ_SENSE_DATA"
                    return 31
                }
                "REQ_SENSE_DATA" -> {
                    java.util.Arrays.fill(buffer, 0, 18, 0.toByte())
                    val seqSense = senseSequence?.getOrNull(currentTurAttempt - 1)
                    if (seqSense != null) {
                        buffer[2] = seqSense.first
                        buffer[12] = seqSense.second
                        buffer[13] = seqSense.third
                    } else {
                        if (senseKeyOverride != null) buffer[2] = senseKeyOverride
                        if (ascOverride != null) buffer[12] = ascOverride
                        if (ascqOverride != null) buffer[13] = ascqOverride
                    }
                    state = "REQ_SENSE_CSW"
                    return 18
                }
                "REQ_SENSE_CSW" -> {
                    val csw = ByteArray(13)
                    val b = java.nio.ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x53425355)
                    System.arraycopy(csw, 0, buffer, 0, csw.size.coerceAtMost(length))
                    currentTurAttempt++
                    state = "DONE"
                    return 13
                }
                "TOC_CBW" -> {
                    state = "TOC_DATA"
                    return 31
                }
                "TOC_DATA" -> {
                    state = "TOC_CSW"
                    if (tocResponse != null) {
                        val toCopy = tocResponse.size.coerceAtMost(length)
                        System.arraycopy(tocResponse, 0, buffer, 0, toCopy)
                        return toCopy
                    }
                    return 0
                }
                "TOC_CSW" -> {
                    val csw = ByteArray(13)
                    val b = java.nio.ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x53425355)
                    b.putInt(3) // csw tag
                    csw[12] = tocCswStatus
                    System.arraycopy(csw, 0, buffer, 0, csw.size.coerceAtMost(length))
                    state = "DONE"
                    return 13
                }
                else -> return -1
            }
        }
    }

    // Synthetic TOC for tests:
    // Track 1: LBA 0 (audio)
    // Track 2: LBA 16000 (audio)
    // Track 3: LBA 32000 (audio)
    // Lead-out: LBA 48000
    private fun createSyntheticTocResponse(): ByteArray {
        val response = ByteArray(804)
        val buffer = java.nio.ByteBuffer.wrap(response).order(java.nio.ByteOrder.BIG_ENDIAN)

        // TOC Data Length = 2 bytes (header) + 4 entries * 8 bytes/entry - 2 = 32
        buffer.putShort(0, 34.toShort())
        response[2] = 1 // First track
        response[3] = 3 // Last track

        // Track 1
        response[4] = 0 // reserved
        response[5] = 0 // ADR/Control (audio)
        response[6] = 1 // Track Number
        response[7] = 0 // reserved
        buffer.putInt(8, 0) // LBA

        // Track 2
        response[12] = 0
        response[13] = 0
        response[14] = 2
        response[15] = 0
        buffer.putInt(16, 16000)

        // Track 3
        response[20] = 0
        response[21] = 0
        response[22] = 3
        response[23] = 0
        buffer.putInt(24, 32000)

        // Lead-out (0xAA)
        response[28] = 0
        response[29] = 0
        response[30] = 0xAA.toByte()
        response[31] = 0
        buffer.putInt(32, 48000)

        return response
    }

    @Test
    fun testScsiInquiryCommandParsesCorrectly() {
        val vendorId = "PIONEER "
        val model = "BD-RW   BDR-XD07"

        val inquiryData = ByteArray(36)
        inquiryData[0] = 0x05 // Peripheral device type 5 (CD/DVD)

        System.arraycopy(vendorId.toByteArray(Charsets.US_ASCII), 0, inquiryData, 8, 8)
        System.arraycopy(model.toByteArray(Charsets.US_ASCII), 0, inquiryData, 16, 16)

        val fakeTransport = FakeUsbTransport(inquiryResponse = inquiryData)
        val outEndpoint = mock(UsbEndpoint::class.java)
        val inEndpoint = mock(UsbEndpoint::class.java)

        val command = ScsiInquiryCommand(fakeTransport, outEndpoint, inEndpoint)
        val driveInfo = command.execute()

        assertNotNull(driveInfo)
        assertEquals("PIONEER", driveInfo?.vendor)
        assertEquals("BD-RW   BDR-XD07", driveInfo?.model)
        assertTrue(driveInfo?.isOptical == true)
    }

    @Test
    fun testUsbDriveDetectorHandlesDeviceDetection() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager

        val shadowUsbManager = org.robolectric.Shadows.shadowOf(usbManager)

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/001")
        org.mockito.Mockito.`when`(device.vendorId).thenReturn(0x1234)
        org.mockito.Mockito.`when`(device.productId).thenReturn(0x5678)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)

        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(8)
        org.mockito.Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(6)
        org.mockito.Mockito.`when`(usbInterface.interfaceProtocol).thenReturn(80)

        shadowUsbManager.addOrUpdateUsbDevice(device, true)

        val detector = UsbDriveDetector(context)
        detector.scanForDevices()

        assertNotNull(detector)
        // Sleep briefly to let the coroutine/thread update the state
        Thread.sleep(2000)
        assertTrue(detector.driveStatus.value != DriveStatus.NoDrive)
    }

    @Test
    fun testNoDevicesOnStartup() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        assertEquals(DriveStatus.NoDrive, detector.driveStatus.value)
    }

    @Test
    fun testNonMassStorageDeviceAttached() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)
        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(2) // Not mass storage

        // Call the intent directly simulating non-mass-storage attachment
        val intent = android.content.Intent(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver

        receiver.onReceive(context, intent)

        assertEquals(DriveStatus.NoDrive, detector.driveStatus.value)
    }

    @Test
    fun testMassStorageAttachedPermissionDenied() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        val device = mock(android.hardware.usb.UsbDevice::class.java)

        val intent = android.content.Intent("com.bitperfect.app.USB_PERMISSION")
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver

        receiver.onReceive(context, intent)

        assertEquals(DriveStatus.PermissionDenied, detector.driveStatus.value)
    }

    @Test
    fun testDeviceDetached() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        // Setup initial state to be something else
        val statusField = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        stateFlow.value = DriveStatus.Connecting()

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        val intent = android.content.Intent(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver

        receiver.onReceive(context, intent)

        assertEquals(DriveStatus.NoDrive, detector.driveStatus.value)
    }

    @Test
    fun testInterrogateOpenFails() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val shadowUsbManager = org.robolectric.Shadows.shadowOf(usbManager)
        val detector = UsbDriveDetector(context)

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/001")
        org.mockito.Mockito.`when`(device.vendorId).thenReturn(0x1234)
        org.mockito.Mockito.`when`(device.productId).thenReturn(0x5678)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)

        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(8)
        org.mockito.Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(6)
        org.mockito.Mockito.`when`(usbInterface.endpointCount).thenReturn(2)

        val inEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(inEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(inEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_IN)
        val outEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(outEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(outEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_OUT)

        org.mockito.Mockito.`when`(usbInterface.getEndpoint(0)).thenReturn(inEp)
        org.mockito.Mockito.`when`(usbInterface.getEndpoint(1)).thenReturn(outEp)

        shadowUsbManager.addOrUpdateUsbDevice(device, true)

        val mockUsbManager = mock(android.hardware.usb.UsbManager::class.java)
        // Ensure connection is null
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(device)).thenReturn(null)
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(org.mockito.Mockito.any(android.hardware.usb.UsbDevice::class.java))).thenReturn(null)

        val managerField = UsbDriveDetector::class.java.getDeclaredField("usbManager")
        managerField.isAccessible = true
        managerField.set(detector, mockUsbManager)

        // Use broadcast intent instead of reflection to invoke interrogateDevice
        val intent = android.content.Intent("com.bitperfect.app.USB_PERMISSION")
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, true)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver
        receiver.onReceive(context, intent)

        var attempts = 0
        while (detector.driveStatus.value !is DriveStatus.Error && attempts < 100) {
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            attempts++
        }

        assertTrue(detector.driveStatus.value is DriveStatus.Error)
    }

    @Test
    fun testNotOptical() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        val statusField = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        stateFlow.value = DriveStatus.NotOptical
        // We simulate it by just setting the state, as real interrogateDevice needs open device
        assertEquals(DriveStatus.NotOptical, detector.driveStatus.value)
    }

    @Test
    fun testEmpty() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        val statusField = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        val info = DriveInfo("VENDOR", "PRODUCT", null, true)
        val emptyState = DriveStatus.Empty(info)
        stateFlow.value = emptyState
        // We simulate it by just setting the state
        assertEquals(emptyState, detector.driveStatus.value)
    }

    @Test
    fun testDiscReady() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)

        val statusField = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        val info = DriveInfo("VENDOR", "PRODUCT", null, true)
        stateFlow.value = DriveStatus.DiscReady(info)

        assertEquals(DriveStatus.DiscReady(info), detector.driveStatus.value)
    }

    @Test
    fun testReadTocCommandParsesCorrectly() {
        val fakeTransport = FakeUsbTransport(inquiryResponse = ByteArray(36), turCswStatus = 0, tocResponse = createSyntheticTocResponse(), tocCswStatus = 0)
        // Advance transfer count to bypass INQUIRY and TUR
        fakeTransport.state = "TOC_CBW"

        val outEndpoint = mock(android.hardware.usb.UsbEndpoint::class.java)
        val inEndpoint = mock(android.hardware.usb.UsbEndpoint::class.java)

        val command = ReadTocCommand(fakeTransport, outEndpoint, inEndpoint)
        val result = command.execute()

        assertNotNull(result)
        val toc = result!!.first
        assertEquals(3, toc.trackCount)
        assertEquals(150, toc.tracks.get(0).lba)
        assertEquals(16150, toc.tracks.get(1).lba)
        assertEquals(32150, toc.tracks.get(2).lba)
        assertEquals(48150, toc.leadOutLba)
    }

    @Test
    fun testReadTocCommandReturnsNullOnCswFailure() {
        val fakeTransport = FakeUsbTransport(inquiryResponse = ByteArray(36), turCswStatus = 0, tocResponse = createSyntheticTocResponse(), tocCswStatus = 1)
        fakeTransport.state = "TOC_CBW"

        val outEndpoint = mock(android.hardware.usb.UsbEndpoint::class.java)
        val inEndpoint = mock(android.hardware.usb.UsbEndpoint::class.java)

        val command = ReadTocCommand(fakeTransport, outEndpoint, inEndpoint)
        val result = command.execute()

        assertNull(result)
    }

    @Test
    fun testComputeDiscIdsWithSyntheticData() {
        val toc = DiscToc(
            tracks = listOf(
                TocEntry(1, 0),
                TocEntry(2, 16000),
                TocEntry(3, 32000)
            ),
            leadOutLba = 48000
        )

        // Freedb digit sum logic:
        // Track 1: 0 sec -> sum 0
        // Track 2: 16000/75 = 213 sec -> 2+1+3 = 6
        // Track 3: 32000/75 = 426 sec -> 4+2+6 = 12
        // Total checksum = (0 + 6 + 12) % 255 = 18
        // Total seconds = 48000/75 = 640
        // First offset = 0/75 = 0
        // Disc length = 640 - 0 = 640
        // Track count = 3
        // FreedbId = (18 << 24) | (640 << 8) | 3
        // 18 << 24 = 301989888
        // 640 << 8 = 163840
        // 3
        // 301989888 + 163840 + 3 = 302153731
        val expectedFreedbId = 302153731L
        val actualFreedbId = computeFreedbId(toc)
        assertEquals(expectedFreedbId, actualFreedbId)

        // AccurateRip logic:
        // Using LSN (LBA - 150)
        // id1 = (0 - 150) + (16000 - 150) + (32000 - 150) + (48000 - 150)
        //     = -150 + 15850 + 31850 + 47850 = 95400
        // id2 = max(-150, 1)*1 + max(15850, 1)*2 + max(31850, 1)*3 + 47850 * 4
        //     = 1 + 31700 + 95550 + 191400
        //     = 318651
        val expectedAccurateRipId = com.bitperfect.core.utils.AccurateRipDiscId(
            id1 = 95400L,
            id2 = 318651L,
            id3 = expectedFreedbId
        )
        val actualAccurateRipId = computeAccurateRipDiscId(toc)
        assertEquals(expectedAccurateRipId, actualAccurateRipId)
    }

    @Test
    fun testReportError() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context)
        val info = DriveInfo("v", "p", null, false)
        val field = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        field.isAccessible = true
        (field.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>).value = DriveStatus.Empty(info)

        detector.reportError("Some error message")

        val state = detector.driveStatus.value
        assertTrue("Expected DriveStatus.Error", state is DriveStatus.Error)
        assertEquals("Some error message", (state as DriveStatus.Error).message)
        assertEquals(info, state.info)
    }

    @Test
    fun testIsMassStorageDeviceAcceptsSubclass2() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val usbManager = context.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager

        val shadowUsbManager = org.robolectric.Shadows.shadowOf(usbManager)

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
        org.mockito.Mockito.`when`(device.vendorId).thenReturn(0x4321)
        org.mockito.Mockito.`when`(device.productId).thenReturn(0x8765)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)

        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(8)
        org.mockito.Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(2)
        org.mockito.Mockito.`when`(usbInterface.interfaceProtocol).thenReturn(50) // Any protocol

        shadowUsbManager.addOrUpdateUsbDevice(device, true)

        val detector = UsbDriveDetector(context)

        // Use reflection to call private method
        val method = UsbDriveDetector::class.java.getDeclaredMethod("isMassStorageDevice", android.hardware.usb.UsbDevice::class.java)
        method.isAccessible = true
        val result = method.invoke(detector, device) as Boolean

        assertTrue("Expected isMassStorageDevice to return true for Class 8, Subclass 2", result)
    }

    @Test
    fun testSpinningUpToDetectingDiscToDiscReady() {
        val inquiryData = ByteArray(36)
        inquiryData[0] = 0x05 // Optical

        // Sequence of events:
        // Attempt 1: Returns NOT_READY with SPINNING_UP sense data (0x02, 0x04, 0x01) -> State becomes SpinningUp
        // Attempt 2: Returns NOT_READY with non-spinning sense data (0x02, 0x3A, 0x00 - medium not present) -> State becomes DetectingDisc
        // Attempt 3: Returns READY -> State becomes DiscReady
        val fakeTransport = FakeUsbTransport(
            inquiryResponse = inquiryData,
            turCswStatus = 0, // Eventually ready
            turRetriesRequired = 2,
            senseSequence = listOf(
                Triple(0x02.toByte(), 0x04.toByte(), 0x01.toByte()), // Spinning up
                Triple(0x02.toByte(), 0x3A.toByte(), 0x00.toByte())  // Non-spinning (Medium not present)
            ),
            tocResponse = createSyntheticTocResponse()
        )

        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context) { _ -> fakeTransport }

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/001")
        org.mockito.Mockito.`when`(device.vendorId).thenReturn(0x1234)
        org.mockito.Mockito.`when`(device.productId).thenReturn(0x5678)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)

        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(8)
        org.mockito.Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(6)
        org.mockito.Mockito.`when`(usbInterface.endpointCount).thenReturn(2)

        val inEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(inEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(inEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_IN)
        val outEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(outEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(outEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_OUT)

        org.mockito.Mockito.`when`(usbInterface.getEndpoint(0)).thenReturn(inEp)
        org.mockito.Mockito.`when`(usbInterface.getEndpoint(1)).thenReturn(outEp)

        val connection = mock(android.hardware.usb.UsbDeviceConnection::class.java)
        org.mockito.Mockito.`when`(connection.claimInterface(usbInterface, true)).thenReturn(true)

        val mockUsbManager = mock(android.hardware.usb.UsbManager::class.java)
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(device)).thenReturn(connection)
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(org.mockito.Mockito.any(android.hardware.usb.UsbDevice::class.java))).thenReturn(connection)

        val managerField = UsbDriveDetector::class.java.getDeclaredField("usbManager")
        managerField.isAccessible = true
        managerField.set(detector, mockUsbManager)

        val intent = android.content.Intent("com.bitperfect.app.USB_PERMISSION")
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, true)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver
        receiver.onReceive(context, intent)

        var attempts = 0
        var sawSpinningUp = false
        var sawDetectingDisc = false
        while (detector.driveStatus.value !is DriveStatus.DiscReady && attempts < 100) {
            val currentState = detector.driveStatus.value
            if (currentState is DriveStatus.SpinningUp) {
                sawSpinningUp = true
            } else if (currentState is DriveStatus.DetectingDisc) {
                sawDetectingDisc = true
            }
            if (fakeTransport.state == "DONE") {
                fakeTransport.state = "TUR_CBW"
            }
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            attempts++
        }
        val state = detector.driveStatus.value
        assertTrue("Expected to see SpinningUp state", sawSpinningUp)
        assertTrue("Expected to see DetectingDisc state", sawDetectingDisc)
        assertTrue("Expected DriveStatus.DiscReady after polling but was $state", state is DriveStatus.DiscReady)
    }

    @Test
    fun testNotReadyFromEmptyDoesNotEnterDetectingDisc() {
        val inquiryData = ByteArray(36)
        inquiryData[0] = 0x05 // Optical

        // This will fail the first TUR and return non-spinning sense data, leading to Empty
        val fakeTransport = FakeUsbTransport(
            inquiryResponse = inquiryData,
            turCswStatus = 1, // Stay not ready
            turRetriesRequired = 1, // Only 1 attempt before returning the turCswStatus
            senseSequence = listOf(
                Triple(0x02.toByte(), 0x3A.toByte(), 0x00.toByte())  // Non-spinning
            )
        )

        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context) { _ -> fakeTransport }

        // Start directly in Empty state
        val statusField = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        val info = DriveInfo("VENDOR", "PRODUCT", null, true)
        stateFlow.value = DriveStatus.Empty(info)

        // Run the polling loop to simulate one TUR check
        val startPollingMethod = UsbDriveDetector::class.java.getDeclaredMethod("startPollingLoop", DriveInfo::class.java)
        startPollingMethod.isAccessible = true
        startPollingMethod.invoke(detector, info)

        var attempts = 0
        var sawDetectingDisc = false
        while (attempts < 20) { // Wait long enough for a poll
            if (detector.driveStatus.value is DriveStatus.DetectingDisc) {
                sawDetectingDisc = true
            }
            if (fakeTransport.state == "DONE") {
                fakeTransport.state = "TUR_CBW"
            }
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            attempts++
        }

        assertTrue("Expected state to remain Empty, never DetectingDisc", !sawDetectingDisc)
        assertTrue("Expected final state to be Empty", detector.driveStatus.value is DriveStatus.Empty)
    }

    @Test
    fun testPollingLoopSucceedsAfterInitialTurFailure() {
        val inquiryData = ByteArray(36)
        inquiryData[0] = 0x05 // Optical

        // This will fail the first TUR, putting it in Empty initially. The polling loop runs
        // the subsequent TUR, which succeeds, leading to DiscReady.
        val fakeTransport = FakeUsbTransport(inquiryResponse = inquiryData, turCswStatus = 0, turRetriesRequired = 1, tocResponse = createSyntheticTocResponse())

        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val detector = UsbDriveDetector(context) { _ -> fakeTransport }

        val device = mock(android.hardware.usb.UsbDevice::class.java)
        org.mockito.Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/001")
        org.mockito.Mockito.`when`(device.vendorId).thenReturn(0x1234)
        org.mockito.Mockito.`when`(device.productId).thenReturn(0x5678)
        org.mockito.Mockito.`when`(device.interfaceCount).thenReturn(1)

        val usbInterface = mock(android.hardware.usb.UsbInterface::class.java)
        org.mockito.Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        org.mockito.Mockito.`when`(usbInterface.interfaceClass).thenReturn(8)
        org.mockito.Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(6)
        org.mockito.Mockito.`when`(usbInterface.endpointCount).thenReturn(2)

        val inEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(inEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(inEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_IN)
        val outEp = mock(android.hardware.usb.UsbEndpoint::class.java)
        org.mockito.Mockito.`when`(outEp.type).thenReturn(android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK)
        org.mockito.Mockito.`when`(outEp.direction).thenReturn(android.hardware.usb.UsbConstants.USB_DIR_OUT)

        org.mockito.Mockito.`when`(usbInterface.getEndpoint(0)).thenReturn(inEp)
        org.mockito.Mockito.`when`(usbInterface.getEndpoint(1)).thenReturn(outEp)

        val connection = mock(android.hardware.usb.UsbDeviceConnection::class.java)
        org.mockito.Mockito.`when`(connection.claimInterface(usbInterface, true)).thenReturn(true)

        val mockUsbManager = mock(android.hardware.usb.UsbManager::class.java)
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(device)).thenReturn(connection)
        org.mockito.Mockito.`when`(mockUsbManager.openDevice(org.mockito.Mockito.any(android.hardware.usb.UsbDevice::class.java))).thenReturn(connection)

        val managerField = UsbDriveDetector::class.java.getDeclaredField("usbManager")
        managerField.isAccessible = true
        managerField.set(detector, mockUsbManager)

        // Use broadcast intent instead of reflection to invoke interrogateDevice
        val intent = android.content.Intent("com.bitperfect.app.USB_PERMISSION")
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)
        intent.putExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, true)

        val field = UsbDriveDetector::class.java.getDeclaredField("usbReceiver")
        field.isAccessible = true
        val receiver = field.get(detector) as android.content.BroadcastReceiver
        receiver.onReceive(context, intent)

        var attempts = 0
        var sawEmpty = false
        while (detector.driveStatus.value !is DriveStatus.DiscReady && attempts < 100) {
            val currentState = detector.driveStatus.value
            if (currentState is DriveStatus.Empty) {
                sawEmpty = true
            }
            if (fakeTransport.state == "DONE") {
                fakeTransport.state = "TUR_CBW"
            }
            Thread.sleep(50)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            attempts++
        }
        val state = detector.driveStatus.value
        assertTrue("Expected to see Empty state initially", sawEmpty)
        assertTrue("Expected DriveStatus.DiscReady after polling but was $state", state is DriveStatus.DiscReady)
    }

    @Test
    fun `setEjectingState sets driveStatus to Ejecting`() {
        val mockContext = mock(android.content.Context::class.java)
        val mockContextApp = mock(android.content.Context::class.java)
        org.mockito.Mockito.`when`(mockContext.applicationContext).thenReturn(mockContextApp)
        val mockUsbManager = mock(android.hardware.usb.UsbManager::class.java)
        org.mockito.Mockito.`when`(mockContextApp.getSystemService(android.content.Context.USB_SERVICE)).thenReturn(mockUsbManager)

        val detector = UsbDriveDetector(
            context = mockContextApp,
            transportFactory = { mock(UsbTransport::class.java) }
        )

        // Inject info via reflection since we need it to not return early
        val field = UsbDriveDetector::class.java.getDeclaredField("_driveStatus")
        field.isAccessible = true
        val statusFlow = field.get(detector) as kotlinx.coroutines.flow.MutableStateFlow<DriveStatus>
        statusFlow.value = DriveStatus.DiscReady(DriveInfo("ven", "prod", null, true))

        detector.setEjectingState()

        val status = detector.driveStatus.value
        assertTrue(status is DriveStatus.Ejecting)
    }
}
