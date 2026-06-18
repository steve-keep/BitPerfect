package com.bitperfect.plugin.wiim

import android.content.Context
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.OutputPluginRegistry

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
class WiimOutputPluginTest {

    @Test
    fun `deviceType is upnp_wiim`() {
        val plugin = WiimOutputPlugin(mockk(relaxed = true))
        assertEquals("upnp_wiim", plugin.deviceType)
    }

    @Test
    fun `attach forwards discovered devices to registry`() =
        runTest(UnconfinedTestDispatcher()) {
            val context = mockk<Context>(relaxed = true)
            val registry = mockk<OutputPluginRegistry>(relaxed = true)
            val devicesFlow = MutableStateFlow<List<OutputDevice.Upnp>>(emptyList())
            val manager = mockk<UpnpDiscoveryManager>(relaxed = true)
            every { manager.devices } returns devicesFlow

            val plugin = WiimOutputPlugin(context) { manager }
            plugin.attach(registry)

            val device = OutputDevice.Upnp(
                udn = "uuid:test",
                friendlyName = "Test WiiM",
                manufacturer = "WiiM",
                modelName = "",
                deviceDescriptionUrl = "",
                avTransportControlUrl = "",
                renderingControlUrl = "",
                ipAddress = "192.168.1.42"
            )
            devicesFlow.value = listOf(device)

            verify { registry.updateDevices("upnp_wiim", listOf(device)) }
        }

    @Test
    fun `release stops discovery manager`() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<UpnpDiscoveryManager>(relaxed = true)
        every { manager.devices } returns MutableStateFlow(emptyList())

        val plugin = WiimOutputPlugin(context) { manager }
        plugin.attach(mockk(relaxed = true))
        plugin.release()

        verify { manager.stop() }
    }
}
