package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.bitperfect.app.usb.DriveInfo
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
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
                    DriveStatus.DiscReady(dummyInfo, null),
                    "Disc Ready",
                    "Looking up metadata…"
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
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            DeviceList(driveStatus = driveStatus, viewModel = mockViewModel)
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(expectedHeadline).assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedSubtitle).assertIsDisplayed()
    }

    @Test
    fun verifyDiscReadyCard_withMetadata() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        val testMetadata = DiscMetadata("Test Album", "Test Artist", emptyList(), "mb123")

        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(testMetadata))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow("http://example.com/art.jpg"))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(true))

        val dummyInfo = DriveInfo("ASUS", "BW-16D1HT", true)
        val dummyToc = DiscToc((1..10).map { com.bitperfect.core.models.TocEntry(it, 150 * it) }, 2000)
        val status = DriveStatus.DiscReady(dummyInfo, dummyToc)

        composeTestRule.setContent {
            DeviceList(driveStatus = status, viewModel = mockViewModel)
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Test Album").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Artist").assertIsDisplayed()
        composeTestRule.onNodeWithText("10 tracks").assertIsDisplayed()
    }

    @Test
    fun verifyDiscReadyCard_withoutMetadata() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)

        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val dummyInfo = DriveInfo("ASUS", "BW-16D1HT", true)
        val dummyToc = DiscToc((1..10).map { com.bitperfect.core.models.TocEntry(it, 150 * it) }, 2000)
        val status = DriveStatus.DiscReady(dummyInfo, dummyToc)

        composeTestRule.setContent {
            DeviceList(driveStatus = status, viewModel = mockViewModel)
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Disc Ready").assertIsDisplayed()
        composeTestRule.onNodeWithText("Looking up metadata…").assertIsDisplayed()
        composeTestRule.onNodeWithText("10 tracks").assertIsDisplayed()
    }
}
