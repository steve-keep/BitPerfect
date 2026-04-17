package com.bitperfect.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndRipTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionRule: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @Before
    fun setup() {
        // Clear SharedPreferences to ensure test isolation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testVirtualDriveToggleAndSelection() {
        // 1. Go to Settings (using the bottom navigation tab)
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()

        // 2. Toggle "Enable Virtual Drive"
        composeTestRule.onNode(hasText("Enable Virtual Drive", substring = true) and hasClickAction()).performClick()

        // Check if "Selected Test CD" header appeared (it only shows if enabled)
        composeTestRule.waitUntil(20000) {
            composeTestRule.onAllNodesWithText("Selected Test CD").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Select a different CD
        composeTestRule.onNodeWithText("Thriller", substring = true).performClick()

        // 4. Go back
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 5. Check if Virtual Drive appears in Device List
        composeTestRule.waitUntil(20000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().assertExists()
    }

    @Test
    fun testStartRipCrash() {
        // 1. Enable Virtual Drive
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()
        composeTestRule.onNode(hasText("Enable Virtual Drive", substring = true) and hasClickAction()).performClick()

        // Use back icon button specifically
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 2. Wait for Device List and select Virtual Drive
        composeTestRule.waitUntil(20000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().performClick()

        // 3. Wait for "Ready" status
        composeTestRule.waitUntil(20000) {
            composeTestRule.onAllNodesWithText("Ready", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Start Rip
        composeTestRule.onNode(hasText("Start Secure Rip", substring = true) and hasClickAction()).assertExists()
        composeTestRule.onNode(hasText("Start Secure Rip", substring = true) and hasClickAction()).performClick()

        // 5. Verify no crash and progress starts
        composeTestRule.waitUntil(30000) {
            composeTestRule.onAllNodesWithText("Ripping Status", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun testTrackListVisibility() {
        // 1. Enable Virtual Drive
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()
        composeTestRule.onNode(hasText("Enable Virtual Drive", substring = true) and hasClickAction()).performClick()
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 2. Select Virtual Drive
        composeTestRule.waitUntil(20000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().performClick()

        // 3. Verify Track List appears
        composeTestRule.waitUntil(30000) {
            composeTestRule.onAllNodesWithText("Track List", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Track List").assertExists()
        // Our default test CD (Thriller) should have its tracks
        composeTestRule.onNodeWithText("Track 1", substring = true).assertExists()
        composeTestRule.onNodeWithText("Total Duration", substring = true).assertExists()
    }
}
