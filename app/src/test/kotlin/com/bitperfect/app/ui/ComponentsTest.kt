package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito
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
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        Mockito.`when`(mockViewModel.driveStatus).thenReturn(MutableStateFlow(DriveStatus.Connecting(null)))
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val emptyBannerState = RipBannerState(false, 0, 0, 0f, "", "", null)
        composeTestRule.setContent {
            DeviceList(viewModel = mockViewModel, bannerState = emptyBannerState)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun verifyEmptyState() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        Mockito.`when`(mockViewModel.driveStatus).thenReturn(MutableStateFlow(DriveStatus.Empty(driveInfo)))
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val emptyBannerState = RipBannerState(false, 0, 0, 0f, "", "", null)
        composeTestRule.setContent {
            DeviceList(viewModel = mockViewModel, bannerState = emptyBannerState)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("No Disc Inserted").assertIsDisplayed()
    }

    @Test
    fun verifyDiscReadyState() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        Mockito.`when`(mockViewModel.driveStatus).thenReturn(MutableStateFlow(DriveStatus.DiscReady(driveInfo)))
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val emptyBannerState = RipBannerState(false, 0, 0, 0f, "", "", null)
        composeTestRule.setContent {
            DeviceList(viewModel = mockViewModel, bannerState = emptyBannerState)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Disc Ready").assertIsDisplayed()
    }
}
