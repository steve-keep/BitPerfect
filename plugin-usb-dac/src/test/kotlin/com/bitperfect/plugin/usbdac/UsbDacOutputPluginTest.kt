package com.bitperfect.plugin.usbdac

import android.content.Context
import android.hardware.usb.UsbDevice
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.OutputPluginRegistry
import com.bitperfect.core.output.UacProtocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbDacOutputPluginTest {

    @Test
    fun `deviceType is usb_dac`() {
        val plugin = UsbDacOutputPlugin(mockk(relaxed = true), kotlinx.coroutines.flow.MutableStateFlow(0.10f))
        assertEquals("usb_dac", plugin.deviceType)
    }

    @Test
    fun `attach registers connected device with registry`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockk<Context>(relaxed = true)
        val registry = mockk<OutputPluginRegistry>(relaxed = true)

        val usbDevice = mockk<UsbDevice>(relaxed = true)
        every { usbDevice.deviceId } returns 42

        val mockDetector = mockk<UsbAudioDacDetector>(relaxed = true)
        val stateFlow = MutableStateFlow<UsbDacState>(
            UsbDacState.Connected(usbDevice, UacProtocol.UAC2, "Test DAC")
        )
        every { mockDetector.state } returns stateFlow

        val plugin = UsbDacOutputPlugin(
            appContext = context,
            usbDacVolumeFlow = kotlinx.coroutines.flow.MutableStateFlow(0.10f),
            detectorFactory = { mockDetector }
        )

        plugin.attach(registry)

        val device = OutputDevice.UsbDac(usbDevice, UacProtocol.UAC2, "Test DAC")
        verify { registry.updateDevices("usb_dac", match { it.size == 1 && it[0] is OutputDevice.UsbDac }) }
    }

    @Test
    fun `attach clears devices when DAC absent`() = runTest(UnconfinedTestDispatcher()) {
        val context = mockk<Context>(relaxed = true)
        val registry = mockk<OutputPluginRegistry>(relaxed = true)

        val mockDetector = mockk<UsbAudioDacDetector>(relaxed = true)
        val stateFlow = MutableStateFlow<UsbDacState>(UsbDacState.Absent)
        every { mockDetector.state } returns stateFlow

        val plugin = UsbDacOutputPlugin(
            appContext = context,
            usbDacVolumeFlow = kotlinx.coroutines.flow.MutableStateFlow(0.10f),
            detectorFactory = { mockDetector }
        )

        plugin.attach(registry)

        verify { registry.updateDevices("usb_dac", emptyList()) }
    }
}
