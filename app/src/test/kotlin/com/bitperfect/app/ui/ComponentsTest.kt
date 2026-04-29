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
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.Connecting(null), viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun verifyEmptyState() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.Empty(driveInfo), viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("No Disc Inserted").assertIsDisplayed()
    }

    @Test
    fun verifyDiscReadyState() {
        val mockViewModel = Mockito.mock(AppViewModel::class.java)
        Mockito.`when`(mockViewModel.discMetadata).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.coverArtUrl).thenReturn(MutableStateFlow(null))
        Mockito.`when`(mockViewModel.isKeyDisc).thenReturn(MutableStateFlow(false))

        val driveInfo = DriveInfo("ASUS", "BW-16D1HT", true, 0, 0, "")
        composeTestRule.setContent {
            DeviceList(driveStatus = DriveStatus.DiscReady(driveInfo), viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("Disc Ready").assertIsDisplayed()
    }
}
