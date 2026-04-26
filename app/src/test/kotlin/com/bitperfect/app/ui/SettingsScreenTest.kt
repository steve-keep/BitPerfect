package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bitperfect.core.utils.SettingsManager
import com.bitperfect.core.services.DriveOffsetRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifySettingsScreenRenders() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val mockViewModel = AppViewModel(application)
        val settingsManager = SettingsManager(application)
        val driveOffsetRepository = DriveOffsetRepository(application)

        composeTestRule.setContent {
            SettingsScreen(
                settingsManager = settingsManager,
                driveOffsetRepository = driveOffsetRepository,
                viewModel = mockViewModel,
                onNavigateToAbout = {}
            )
        }

        composeTestRule.onNodeWithText("Send Debug Info").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("About").performScrollTo().assertIsDisplayed()
    }
}
