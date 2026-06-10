package com.bitperfect.app.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbAudioDacDetectorTest {

    private lateinit var context: Context
    private lateinit var usbManager: UsbManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        usbManager = mockk(relaxed = true)
        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startup scan ignores non-audio devices`() = runTest {
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
    }
}
