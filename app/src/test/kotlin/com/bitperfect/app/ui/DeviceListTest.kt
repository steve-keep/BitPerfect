package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.bitperfect.app.usb.DriveInfo
import com.bitperfect.app.usb.DriveStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DeviceListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testNoDrive() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.NoDrive) }
        composeTestRule.onNodeWithText("No Drive Connected").assertIsDisplayed()
    }

    @Test
    fun testConnecting() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.Connecting) }
        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun testPermissionDenied() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.PermissionDenied) }
        composeTestRule.onNodeWithText("Access Denied").assertIsDisplayed()
    }

    @Test
    fun testNotOptical() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.NotOptical) }
        composeTestRule.onNodeWithText("Unsupported Device").assertIsDisplayed()
    }

    @Test
    fun testEmpty() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.Empty) }
        composeTestRule.onNodeWithText("No Disc Inserted").assertIsDisplayed()
    }

    @Test
    fun testDiscReady() {
        val info = DriveInfo("VENDOR", "PRODUCT", true)
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.DiscReady(info)) }
        composeTestRule.onNodeWithText("Disc Ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("VENDOR · PRODUCT").assertIsDisplayed()
    }

    @Test
    fun testError() {
        composeTestRule.setContent { DeviceList(driveStatus = DriveStatus.Error("Custom Error")) }
        composeTestRule.onNodeWithText("Drive Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom Error").assertIsDisplayed()
    }
}
