package com.bitperfect.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
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
class LaunchTestRobolectricTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceController = Robolectric.buildService(RippingService::class.java).create()
        val service = serviceController.get()

        shadowOf(ApplicationProvider.getApplicationContext<Context>() as android.app.Application)
            .setComponentNameAndServiceForBindService(
                ComponentName(context, RippingService::class.java),
                service.onBind(Intent())
            )
    }

    // Scenario: "app launches and displays initial ui"
    @Test
    fun appLaunchesAndDisplaysInitialUi() = runTest {
        ActivityScenario.launch(MainActivity::class.java).use {
            shadowOf(Looper.getMainLooper()).idle()

            composeTestRule.onNodeWithTag("status_label").assertTextEquals("BitPerfect")
        }
    }
}
