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
class CapabilityDetectionTest {

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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testCapabilityDetectionDisplay() {
        // Wait for app to be ready
        composeTestRule.waitForIdle()

        // 1. Go to Settings
        // Use more specific matcher for the NavigationBarItem
        composeTestRule.onNode(hasText("Settings") and hasClickAction()).performClick()

        // 2. Enable Virtual Drive
        composeTestRule.onNode(hasText("Enable Virtual Drive", substring = true)).performClick()

        // 3. Go back to Device List
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 4. Select Virtual Drive
        // The virtual drive might take a moment to appear after returning from settings
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().performClick()

        // 5. Verify Hardware Information is displayed
        // RippingEngine.detectCapabilities issues INQUIRY which VirtualScsiDriver handles
        composeTestRule.waitUntil(15000) {
            composeTestRule.onAllNodesWithText("BITPERF VIRTUAL DRIVE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 6. Verify Capability Badges
        composeTestRule.onNodeWithText("Accurate Stream").assertExists()
        composeTestRule.onNodeWithText("C2 Error Pointers").assertExists()
        composeTestRule.onNodeWithText("Cache detected").assertExists()

        // 7. Verify Read Offset
        composeTestRule.onNodeWithText("Read Offset:", substring = true).assertExists()
    }
}
