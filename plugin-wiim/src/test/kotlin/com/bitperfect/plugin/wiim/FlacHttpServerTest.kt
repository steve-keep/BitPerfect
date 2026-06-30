package com.bitperfect.plugin.wiim

import android.content.Context
import com.bitperfect.core.output.TrackInfo
import fi.iki.elonen.NanoHTTPD
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class FlacHttpServerTest {

    private lateinit var context: Context
    private lateinit var server: FlacHttpServer

    @Before
    fun setup() {
        context = mockk<Context>(relaxed = true)
        val track1 = TrackInfo(
            id = 1L,
            title = "Track One",
            artist = "Artist A",
            albumTitle = "Album",
            durationMs = 120000L,
            filePath = "/fake/path/1.flac",
            trackNumber = 1,
            dataPath = "/fake/data/1",
            albumId = 100L
        )
        val track2 = TrackInfo(
            id = 2L,
            title = "Track Two",
            artist = "Artist A",
            albumTitle = "Album",
            durationMs = 185000L,
            filePath = "/fake/path/2.flac",
            trackNumber = 2,
            dataPath = "/fake/data/2",
            albumId = 100L
        )
        server = FlacHttpServer(context, listOf(track1, track2))
        server.serverIp = "127.0.0.1"
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun teardown() {
        server.stop()
    }

    @Test
    fun `playlist_m3u8 returns correct m3u8 playlist`() {
        val port = server.listeningPort
        val url = URL("http://127.0.0.1:${port}/playlist.m3u8")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        assertEquals(200, connection.responseCode)
        assertEquals("application/vnd.apple.mpegurl", connection.contentType)

        val content = connection.inputStream.bufferedReader().use { it.readText() }

        assertTrue(content.contains("#EXTM3U"))
        assertTrue(content.contains("#EXTINF:120,Track One"))
        assertTrue(content.contains("http://127.0.0.1:${port}/track/1.flac"))
        assertTrue(content.contains("#EXTINF:185,Track Two"))
        assertTrue(content.contains("http://127.0.0.1:${port}/track/2.flac"))
    }
}
