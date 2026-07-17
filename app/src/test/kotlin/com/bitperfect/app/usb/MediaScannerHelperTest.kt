package com.bitperfect.app.usb

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.core.utils.AppLogger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaScannerHelperTest {

    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        logs.clear()
        AppLogger.logCallback = { tag, message, level, throwable ->
            if (tag == "MediaScannerHelper") {
                logs.add(message)
                if (throwable != null) {
                    logs.add("Exception: ${throwable.message}")
                }
            }
        }
    }

    @After
    fun teardown() {
        AppLogger.logCallback = null
    }

    @Test
    fun testPrimaryVolumeUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.flac")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MediaScannerHelper.scanSafUri(context, uri)
        assertTrue(logs.contains("Scanning path: /storage/emulated/0/Music/song.flac"))
    }

    @Test
    fun testSecondaryVolumeUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/1234-5678%3AMusic%2Fsong.flac")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MediaScannerHelper.scanSafUri(context, uri)
        assertTrue(logs.contains("Scanning path: /storage/1234-5678/Music/song.flac"))
    }

    @Test
    fun testInvalidUriEmptyDocument() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MediaScannerHelper.scanSafUri(context, uri)
        // Should return early, no scan log
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testInvalidUriMissingParts() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/just_a_path")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MediaScannerHelper.scanSafUri(context, uri)
        // Should return early
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testExceptionHandling() {
        // Malformed escape sequence throws IllegalArgumentException in URLDecoder
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        MediaScannerHelper.scanSafUri(context, uri)

        // Ensure error is logged
        assertTrue(logs.any { it.startsWith("Failed to parse and scan URI") })
        assertTrue(logs.any { it.startsWith("Exception: URLDecoder: Incomplete trailing escape (%) pattern") })
    }
}
