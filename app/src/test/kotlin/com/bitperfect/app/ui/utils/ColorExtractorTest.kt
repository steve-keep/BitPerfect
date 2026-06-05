package com.bitperfect.app.ui.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorExtractorTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val PREFS_NAME = "color_extractor_cache"

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun getCachedColor_returnsNullWhenUriIsNull() {
        assertNull(ColorExtractor.getCachedColor(context, null))
    }

    @Test
    fun getCachedColor_returnsNullWhenNotCached() {
        assertNull(ColorExtractor.getCachedColor(context, "http://example.com/not_cached.jpg"))
    }

    @Test
    fun getCachedColor_returnsColorWhenCachedInPrefs() {
        val uri = "http://example.com/cached.jpg"
        val expectedColorValue = android.graphics.Color.RED
        prefs.edit().putInt(uri, expectedColorValue).commit()

        val cachedColor = ColorExtractor.getCachedColor(context, uri)
        assertNotNull(cachedColor)
        assertEquals(Color(expectedColorValue), cachedColor)
    }
}
