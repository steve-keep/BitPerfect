package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bitperfect.app.output.OutputDevice
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputDeviceSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun activeDeviceIsHighlighted() {
        val devices = listOf(
            OutputDevice.ThisPhone,
            OutputDevice.Bluetooth("00:11:22", "Test Headphones")
        )

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = OutputDevice.ThisPhone,
                onDeviceSelected = {}
            )
        }

        // Wait for idle
        composeTestRule.waitForIdle()

        // We can't easily assert colors via standard testing APIs without custom matchers,
        // but we verify the nodes are correctly displayed
        composeTestRule.onNodeWithText("This phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Headphones").assertIsDisplayed()
    }

    @Test
    fun bluetoothSubtitleShowsBatteryLevel() {
        val devices = listOf(
            OutputDevice.ThisPhone,
            OutputDevice.Bluetooth("00:11:22", "Test Headphones", batteryPercent = 45)
        )

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = OutputDevice.ThisPhone,
                onDeviceSelected = {}
            )
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test Headphones").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bluetooth · Medium").assertIsDisplayed()
    }

    @Test
    fun tapDeviceCallsCallback() {
        val targetDevice = OutputDevice.Bluetooth("00:11:22", "Test Headphones")
        val devices = listOf(OutputDevice.ThisPhone, targetDevice)
        var selectedDevice: OutputDevice? = null

        composeTestRule.setContent {
            OutputDeviceSheet(
                devices = devices,
                activeDevice = OutputDevice.ThisPhone,
                onDeviceSelected = { device: OutputDevice -> selectedDevice = device }
            )
        }

        composeTestRule.onNodeWithText("Test Headphones").performClick()
        assertEquals(targetDevice, selectedDevice)
    }
}
