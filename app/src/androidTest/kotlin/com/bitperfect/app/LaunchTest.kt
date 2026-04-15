package com.bitperfect.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun app_launches_and_displays_initial_ui() {
        // Verify that the "Select USB Drive" text is displayed,
        // which indicates the MainActivity has successfully loaded the DeviceList component.
        composeTestRule.onNodeWithText("Select USB Drive").assertExists()
    }
}
