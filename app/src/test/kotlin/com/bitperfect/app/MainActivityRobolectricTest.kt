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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMainActivityLaunchesAndShowsBitPerfect() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                composeTestRule.onNodeWithTag("status_label").assertIsDisplayed()
            }
        }
    }
}
