package com.bitperfect.core.services

import android.content.Context
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

class LyricsRepositoryTest {

    private val mockContext = mockk<Context>(relaxed = true)

    @Test
    fun `fetchReturnsLyricsOnSuccess`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "plainLyrics": "Hello world",
                    "syncedLyrics": "[00:01.00] Hello world"
                }
            """.trimIndent()

            respond(
                content = jsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        // Mock cacheDir to avoid NPE when creating the File object
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "lyrics_test_cache_" + System.currentTimeMillis())
        cacheDir.mkdirs()
        cacheDir.deleteOnExit()
        io.mockk.every { mockContext.cacheDir } returns cacheDir

        val repository = LyricsRepository(mockContext, mockEngine)
        val result = repository.fetch(
            artistName = "Artist",
            albumTitle = "Album",
            trackTitle = "Track",
            trackNumber = 1,
            mbReleaseId = "valid-id",
            durationSeconds = 120.0
        ) as com.bitperfect.core.models.LyricsFetchResult.Success

        assertEquals("Hello world", result.lyrics.plainLyrics)
        assertEquals("[00:01.00] Hello world", result.lyrics.syncedLyrics)
    }

    @Test
    fun `fetchReturnsFailureOn404`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respondError(HttpStatusCode.NotFound)
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "lyrics_test_cache_" + System.currentTimeMillis())
        cacheDir.mkdirs()
        cacheDir.deleteOnExit()
        io.mockk.every { mockContext.cacheDir } returns cacheDir

        val repository = LyricsRepository(mockContext, mockEngine)
        val result = repository.fetch(
            artistName = "Artist",
            albumTitle = "Album",
            trackTitle = "Track",
            trackNumber = 1,
            mbReleaseId = "valid-id",
            durationSeconds = 120.0
        ) as com.bitperfect.core.models.LyricsFetchResult.Failure

        assertEquals(com.bitperfect.core.models.FetchState.NOT_FOUND, result.state)
    }

    @Test
    fun `fetchReturnsFailureWhenResponseIsMissingLyrics`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw IOException("Network error")
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "lyrics_test_cache_" + System.currentTimeMillis())
        cacheDir.mkdirs()
        cacheDir.deleteOnExit()
        io.mockk.every { mockContext.cacheDir } returns cacheDir

        val repository = LyricsRepository(mockContext, mockEngine)
        val result = repository.fetch(
            artistName = "Artist",
            albumTitle = "Album",
            trackTitle = "Track",
            trackNumber = 1,
            mbReleaseId = "valid-id",
            durationSeconds = 120.0
        ) as com.bitperfect.core.models.LyricsFetchResult.Failure

        assertEquals(com.bitperfect.core.models.FetchState.NETWORK_ERROR, result.state)
    }
}
