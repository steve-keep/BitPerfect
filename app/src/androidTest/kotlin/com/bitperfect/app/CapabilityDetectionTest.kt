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
class CapabilityDetectionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testCapabilityDetectionDisplay() {
        // 1. Enable Virtual Drive
        composeTestRule.onNode(hasText("Settings", ignoreCase = true) and hasClickAction()).performClick()
        composeTestRule.onNode(hasText("Enable Virtual Drive", substring = true) and hasClickAction()).performClick()
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        // 2. Select Virtual Drive
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithText("VIRTUAL DRIVE", substring = true, ignoreCase = true).onFirst().performClick()

        // 3. Verify Hardware Information is displayed (from detectCapabilities)
        // Note: VirtualScsiDriver handleInquiry returns "BITPERF VIRTUAL DRIVE"
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodesWithText("BITPERF VIRTUAL DRIVE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Verify Capability Badges
        composeTestRule.onNodeWithText("Accurate Stream").assertExists()
        composeTestRule.onNodeWithText("C2 Error Pointers").assertExists()
        composeTestRule.onNodeWithText("Cache detected").assertExists()

        // 5. Verify Read Offset
        composeTestRule.onNodeWithText("Read Offset:", substring = true).assertExists()
    }
}
