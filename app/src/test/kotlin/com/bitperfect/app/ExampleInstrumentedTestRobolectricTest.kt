package com.bitperfect.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import android.content.ComponentName
import android.content.Intent
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExampleInstrumentedTestRobolectricTest {

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

    // Scenario: "use app context"
    @Test
    fun useAppContext() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Context of the app under test.
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            assertEquals("com.bitperfect.app", appContext.packageName)
        }
    }
}
