package com.bitperfect.plugin.usbdac

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbAudioDacDetectorTest {

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)
        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
    }

    @Test
    fun `startup scan ignores non-audio devices`() {
        val nonAudioDevice = mockk<UsbDevice>(relaxed = true) {
            every { interfaceCount } returns 1
            every { getInterface(0) } returns mockk {
                every { interfaceClass } returns UsbConstants.USB_CLASS_MASS_STORAGE
            }
        }

        every { usbManager.deviceList } returns hashMapOf("1" to nonAudioDevice)

        val detector = UsbAudioDacDetector(context)

        assertEquals(UsbDacState.Absent, detector.state.value)
        verify(exactly = 0) { usbManager.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }

        // Ensure coroutines have finished / clean up
        detector.destroy()
    }
}
