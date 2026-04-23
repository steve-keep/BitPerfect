package com.bitperfect.core.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsManagerTest {

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsManager = SettingsManager(context)
        // Clear prefs before test
        context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testOutputFolderUri_defaultIsNull() {
        assertNull(settingsManager.outputFolderUri)
    }

    @Test
    fun testOutputFolderUri_setAndGet() {
        val testUri = "content://com.android.externalstorage.documents/tree/primary%3ATestFolder"
        settingsManager.outputFolderUri = testUri
        assertEquals(testUri, settingsManager.outputFolderUri)
    }
}
