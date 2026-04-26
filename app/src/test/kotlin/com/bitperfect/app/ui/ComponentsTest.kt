package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.DriveInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifyConnectingState() {
        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.Connecting(null))
        }

        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun verifyEmptyState() {
        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.Empty(driveInfo))
        }

        composeTestRule.onNodeWithText("No Disc Inserted").assertIsDisplayed()
    }

    @Test
    fun verifyDiscReadyState() {
        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.DiscReady(driveInfo))
        }

        composeTestRule.onNodeWithText("Disc Ready").assertIsDisplayed()
    }
}
