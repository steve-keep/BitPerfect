package com.bitperfect.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndRipTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testVirtualDriveToggleAndSelection() {
        // 1. Go to Settings
        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        // 2. Toggle "Enable Virtual Drive"
        // Initially it should be off (assuming fresh install/clear prefs)
        composeTestRule.onNodeWithText("Enable Virtual Drive").assertExists()

        // Click to enable
        composeTestRule.onNodeWithText("Enable Virtual Drive").performClick()

        // Check if "Selected Test CD" header appeared (it only shows if enabled)
        // This will fail if the UI doesn't update
        composeTestRule.onNodeWithText("Selected Test CD").assertExists()

        // 3. Select a different CD
        // Default is usually the first one ("Pink Floyd")
        composeTestRule.onNodeWithText("Thriller").assertExists()
        composeTestRule.onNodeWithText("Thriller").performClick()

        // Verify "Thriller" is selected (RadioButton should be checked)
        // In the current implementation, we might not have an easy way to check the RadioButton state
        // without more specific tags, but we can try to find it by its relationship or just check if it's there.

        // 4. Go back
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 5. Check if Virtual Drive appears in Device List
        composeTestRule.onNodeWithText("BITPERF VIRTUAL DRIVE").assertExists()
    }

    @Test
    fun testStartRipCrash() {
        // 1. Enable Virtual Drive
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithText("Enable Virtual Drive").performClick()
        // Ensure it is enabled (might need to fix it first for this test to pass this point)
        // If it doesn't update, this test might get stuck here.

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 2. Select Virtual Drive
        composeTestRule.onNodeWithText("BITPERF VIRTUAL DRIVE").performClick()

        // 3. Start Rip
        composeTestRule.onNodeWithText("Start Secure Rip").assertExists()
        composeTestRule.onNodeWithText("Start Secure Rip").performClick()

        // 4. Verify no crash and progress starts
        // We look for "Ripping Status" which appears when rip starts
        composeTestRule.onNodeWithText("Ripping Status: Reading TOC...", useUnmergedTree = true).assertExists()
    }
}
