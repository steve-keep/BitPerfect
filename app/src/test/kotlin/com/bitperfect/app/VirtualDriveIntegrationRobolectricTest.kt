package com.bitperfect.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import android.content.ComponentName
import android.content.Intent
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import kotlinx.coroutines.test.runTest

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VirtualDriveIntegrationRobolectricTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sharedPrefs = context.getSharedPreferences("bitperfect_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()

        val serviceController = Robolectric.buildService(RippingService::class.java)
        val service = serviceController.create().get()

        shadowOf(ApplicationProvider.getApplicationContext<Context>() as android.app.Application)
            .setComponentNameAndServiceForBindService(
                ComponentName(context, RippingService::class.java),
                service.onBind(Intent())
            )
    }

    // Scenario: "test virtual drive toggle and selection"
    @Test
    fun testVirtualDriveToggleAndSelection() = runTest {
        ActivityScenario.launch(MainActivity::class.java).use {
            shadowOf(Looper.getMainLooper()).idle()

            // Go to Settings
            composeTestRule.onNodeWithContentDescription("Settings").performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // Enable Virtual Drive
            composeTestRule.onNodeWithText("Enable Virtual Drive").performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // Go back
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            shadowOf(Looper.getMainLooper()).idle()

            // Confirm Virtual Drive is in the list and select it
            val virtualDriveText = "ASUS DRW-24B1ST   a"
            composeTestRule.onNodeWithText(virtualDriveText).assertIsDisplayed()
            composeTestRule.onNodeWithText(virtualDriveText).performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
