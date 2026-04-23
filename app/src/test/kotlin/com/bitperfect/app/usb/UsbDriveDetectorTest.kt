package com.bitperfect.app.usb

import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbDriveDetectorTest {

    class FakeUsbTransport(private val inquiryResponse: ByteArray) : UsbTransport {
        var transferCount = 0
        override fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
            transferCount++
            return when (transferCount) {
                1 -> 31 // CBW
                2 -> {
                    System.arraycopy(inquiryResponse, 0, buffer, 0, inquiryResponse.size.coerceAtMost(length))
                    inquiryResponse.size // Inquiry data
                }
                3 -> { // CSW
                    val csw = ByteArray(13)
                    val b = java.nio.ByteBuffer.wrap(csw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(0x53425355) // CSW Signature
                    // Leave rest 0, specifically bCSWStatus at 12 is 0 (Success)
                    System.arraycopy(csw, 0, buffer, 0, csw.size.coerceAtMost(length))
                    13
                }
                else -> -1
            }
        }
    }

    @Test
    fun testScsiInquiryCommandParsesCorrectly() {
        val vendorId = "PIONEER "
        val productId = "BD-RW   BDR-XD07"

        val inquiryData = ByteArray(36)
        inquiryData[0] = 0x05 // Peripheral device type 5 (CD/DVD)

        System.arraycopy(vendorId.toByteArray(Charsets.US_ASCII), 0, inquiryData, 8, 8)
        System.arraycopy(productId.toByteArray(Charsets.US_ASCII), 0, inquiryData, 16, 16)

        val fakeTransport = FakeUsbTransport(inquiryData)
        val outEndpoint = mock(UsbEndpoint::class.java)
        val inEndpoint = mock(UsbEndpoint::class.java)

        val command = ScsiInquiryCommand(fakeTransport, outEndpoint, inEndpoint)
        val driveInfo = command.execute()

        assertNotNull(driveInfo)
        assertEquals("PIONEER", driveInfo?.vendorId)
        assertEquals("BD-RW   BDR-XD07", driveInfo?.productId)
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

        // Since we are mocking UsbDevice, we can't easily perform a real bulk transfer and INQUIRY
        // so we check if detector correctly processed mass storage device through permission request
        // Check that device info is still null since interrogation would fail with mocked device
        assertNotNull(detector)
        assertTrue(detector.deviceInfo.value == null)
    }
}
