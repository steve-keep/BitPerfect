package com.bitperfect.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndRipTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        // Clear SharedPreferences to ensure test isolation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testVirtualDriveToggleAndSelection() {
        // 1. Go to Settings (using the bottom navigation tab)
        // Use a more flexible matcher for the Settings tab
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()

        // 2. Toggle "Enable Virtual Drive"
        composeTestRule.onNodeWithText("Enable Virtual Drive", substring = true).performClick()

        // Check if "Selected Test CD" header appeared (it only shows if enabled)
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("Selected Test CD").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Select a different CD
        composeTestRule.onNodeWithText("Thriller", substring = true).performClick()

        // 4. Go back
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 5. Check if Virtual Drive appears in Device List
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().assertExists()
    }

    @Test
    fun testStartRipCrash() {
        // 1. Enable Virtual Drive
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Enable Virtual Drive", substring = true).performClick()

        // Use back icon button specifically
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 2. Wait for Device List and select Virtual Drive
        // Use substring match and ignore case for better resilience.
        // We use onFirst() because the manufacturer and product name might both contain "VIRTUAL DRIVE".
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().performClick()

        // 3. Start Rip
        composeTestRule.onNodeWithText("Start Secure Rip").assertExists()
        composeTestRule.onNodeWithText("Start Secure Rip").performClick()

        // 4. Verify no crash and progress starts
        // Wait for "Ripping Status" which appears when rip starts
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("Ripping Status: Reading TOC...", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
