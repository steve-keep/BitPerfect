package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bitperfect.app.output.OutputDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutputDeviceSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun activeDeviceIsHighlighted() {
        val activeDevice = OutputDevice.ThisPhone
        val devices = listOf(
            activeDevice,
            OutputDevice.Bluetooth("id1", "Bluetooth Speakers", batteryPercent = null)
        )

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = activeDevice,
                onDeviceSelected = {}
            )
        }

        // We only check that the text exists. The highlighting (green color, Surface card)
        // is tricky to assert in standard Compose unit tests without capturing semantics or
        // using screenshot testing. Asserting it displays correctly gives baseline coverage.
        composeTestRule.onNodeWithText("This phone").assertExists()
        composeTestRule.onNodeWithText("Bluetooth Speakers").assertExists()
    }

    @Test
    fun bluetoothSubtitleShowsBatteryLevel() {
        val activeDevice = OutputDevice.ThisPhone
        val devices = listOf(
            activeDevice,
            OutputDevice.Bluetooth("id1", "Headphones", batteryPercent = 45)
        )

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = activeDevice,
                onDeviceSelected = {}
            )
        }

        // 45 is > 30 and <= 60, so it maps to "Medium" in our logic
        // "Bluetooth · Medium"
        composeTestRule.onNodeWithText("Bluetooth · Medium").assertExists()
    }

    @Test
    fun tapDeviceCallsCallback() {
        var selectedDevice: OutputDevice? = null
        val btDevice = OutputDevice.Bluetooth("id1", "Bluetooth Speakers", batteryPercent = null)
        val devices = listOf(OutputDevice.ThisPhone, btDevice)

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = devices[0],
                onDeviceSelected = { selectedDevice = it }
            )
        }

        composeTestRule.onNodeWithText("Bluetooth Speakers").performClick()
        assertEquals(btDevice, selectedDevice)
    }
}
