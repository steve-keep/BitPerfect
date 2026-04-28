package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.bitperfect.app.usb.DriveInfo
import com.bitperfect.app.usb.DriveStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

import com.bitperfect.app.MainActivity

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceListTest(
    private val driveStatus: DriveStatus,
    private val expectedHeadline: String,
    private val expectedSubtitle: String
) {

    @get:Rule
    val composeTestRule = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{index}: Status={0}")
        fun data(): Collection<Array<Any>> {
            val dummyInfo = DriveInfo("ASUS", "BW-16D1HT", true)
            return listOf(
                arrayOf(DriveStatus.NoDrive, "No Drive Connected", "Connect a USB CD drive via OTG"),
                arrayOf(DriveStatus.Connecting(), "Connecting…", "Detecting drive capabilities"),
                arrayOf(DriveStatus.PermissionDenied, "Access Denied", "Re-connect and allow access when prompted"),
                arrayOf(DriveStatus.NotOptical, "Unsupported Device", "Connected device is not a CD drive"),
                arrayOf(DriveStatus.Empty(dummyInfo), "No Disc Inserted", "Insert a CD to continue"),
                arrayOf(
                    DriveStatus.DiscReady(dummyInfo),
                    "Disc Ready",
                    "ASUS · BW-16D1HT"
                ),
                arrayOf(
                    DriveStatus.Error("Failed to open USB device"),
                    "Drive Error",
                    "Failed to open USB device"
                )
            )
        }
    }

    @Test
    fun verifyDriveStatusCardContent() {
        composeTestRule.setContent {
            DeviceList(driveStatus = driveStatus)
        }

        composeTestRule.onNodeWithText(expectedHeadline).assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedSubtitle).assertIsDisplayed()
    }
}
