package com.bitperfect.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMainActivityLaunchesAndShowsBitPerfect() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { _ ->
                composeTestRule.onNodeWithTag("status_label").assertIsDisplayed()
            }
        }
    }

    @Test
    fun testMainActivityNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { _ ->
                composeTestRule.onNodeWithTag("status_label").assertIsDisplayed()

                // Click settings icon
                composeTestRule.onNodeWithContentDescription("Settings").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithText("Settings").assertIsDisplayed()

                // Click about
                composeTestRule.onNodeWithText("About").performScrollTo().performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithText("About").assertIsDisplayed()

                // Back to Settings
                composeTestRule.onNodeWithContentDescription("Back").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithText("Settings").assertIsDisplayed()

                // Back to Main
                composeTestRule.onNodeWithContentDescription("Back").performClick()
                composeTestRule.waitForIdle()
            }
        }
    }
}
