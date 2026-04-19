package com.bitperfect.app

import android.content.Context
import android.os.IBinder
import android.content.Intent
import android.os.Binder
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.filterToOne
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import androidx.compose.ui.test.hasText
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowInstrumentation
import android.content.ComponentName
import org.robolectric.Robolectric
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasContentDescription

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CapabilityDetectionRobolectricTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val serviceController = Robolectric.buildService(RippingService::class.java).create()
        val service = serviceController.get()

        shadowOf(ApplicationProvider.getApplicationContext<Context>() as android.app.Application)
            .setComponentNameAndServiceForBindService(
                ComponentName(context, RippingService::class.java),
                service.onBind(Intent())
            )
    }

    // Scenario: "test capability detection display"
    @Test
    fun testCapabilityDetectionDisplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("caps_virtual_0_offsetFromAccurateRip", true)
            .putInt("caps_virtual_0_readOffset", 123)
            .putString("caps_virtual_0_vendor", "ASUS")
            .putString("caps_virtual_0_product", "DRW-24B1ST   a")
            .apply()

        ActivityScenario.launch(MainActivity::class.java).use {
            // Wait for app to be ready
            shadowOf(Looper.getMainLooper()).idle()

            // When go to Settings
            composeTestRule.onNodeWithText("Settings").performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // And enable Virtual Drive
            composeTestRule.onNodeWithText("Enable Virtual Drive").performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // And go back to Device List
            composeTestRule.onNode(hasContentDescription("Back")).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // wait for UI to update
            shadowOf(Looper.getMainLooper()).idle()

            // And select Virtual Drive
            composeTestRule.onNodeWithText("Virtual", substring = true).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // wait for the diagnostics to finish
            shadowOf(Looper.getMainLooper()).idle()

            // Then verify Hardware Information is displayed
            composeTestRule.onNodeWithText("Hardware Information").assertExists()

            // And verify Capability Badges
            composeTestRule.onNodeWithText("Accurate Stream").assertExists()
            composeTestRule.onNodeWithText("C2 Error Pointers").assertExists()
            composeTestRule.onNodeWithText("Cache detected").assertExists()

            // And verify Read Offset
            composeTestRule.onNodeWithText("Read Offset:", substring = true).assertExists()
            composeTestRule.onNodeWithText("+123 samples (from AccurateRip database)", substring = true).assertExists()
        }
    }
}
