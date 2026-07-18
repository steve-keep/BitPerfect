package com.bitperfect.plugin.wiim

import android.content.Context
import android.os.Environment
import com.bitperfect.core.output.TrackInfo
import fi.iki.elonen.NanoHTTPD
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FlacHttpServerTest {

    @Test
    fun serve_withMaliciousPath_returnsForbidden() {
        val context = mockk<Context>()

        // Mock Environment.getExternalStorageDirectory()
        mockkStatic(Environment::class)
        val mockExternalStorage = mockk<File>()
        every { mockExternalStorage.canonicalPath } returns "/storage/emulated/0"
        every { Environment.getExternalStorageDirectory() } returns mockExternalStorage

        // We simulate a path traversal attempt
        val maliciousPath = "/storage/emulated/0/Music/../../../data/data/com.bitperfect.app/shared_prefs/secrets.xml"

        val track = TrackInfo(
            id = 1L,
            title = "Secret",
            artist = "Hacker",
            albumTitle = "Exposed",
            durationMs = 1000L,
            trackNumber = 1,
            dataPath = maliciousPath, // Use malicious path
            filePath = maliciousPath,
            albumId = 1L,
            discNumber = 1,
            isAccurateRipVerified = false
        )

        val server = FlacHttpServer(context, listOf(track))

        val mockSession = mockk<NanoHTTPD.IHTTPSession>()
        every { mockSession.method } returns NanoHTTPD.Method.GET
        every { mockSession.uri } returns "/track/1.flac"

        // Serve the request
        val response = server.serve(mockSession)

        // We expect FORBIDDEN due to the canonical path check
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }
}
