package com.bitperfect.core.services

import android.content.Context
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CoverArtRepositoryTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        cacheDir = context.cacheDir
    }

    @After
    fun teardown() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `302 response returns the Location URL`() = runBlocking {
        val expectedUrl = "https://archive.org/download/mbid-abc/mbid-abc-front.jpg"

        val mockEngine = MockEngine { request ->
            println("Request received: ${request.url}")
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, expectedUrl)
            )
        }

        val repository = CoverArtRepository(context, mockEngine)
        val result = repository.getCoverArtUrl("abc")

        println("TEST RESULT: $result")

        assertEquals(expectedUrl, result)

        val cacheFile = File(cacheDir, "caa_abc.txt")
        assertEquals(true, cacheFile.exists())
        assertEquals(expectedUrl, cacheFile.readText().trim())
    }

    @Test
    fun `404 response returns null`() = runBlocking {
        val mockEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.NotFound
            )
        }

        val repository = CoverArtRepository(context, mockEngine)
        val result = repository.getCoverArtUrl("abc")

        assertNull(result)

        val cacheFile = File(cacheDir, "caa_abc.txt")
        assertEquals(false, cacheFile.exists())
    }

    @Test
    fun `cached result is returned without a network call`() = runBlocking {
        val expectedUrl = "https://archive.org/download/mbid-abc/mbid-abc-front.jpg"

        var requestCount = 0
        val mockEngine = MockEngine { request ->
            requestCount++
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, expectedUrl)
            )
        }

        val repository = CoverArtRepository(context, mockEngine)

        // First call
        val result1 = repository.getCoverArtUrl("abc")
        assertEquals(expectedUrl, result1)
        assertEquals(1, requestCount)

        // Second call
        val result2 = repository.getCoverArtUrl("abc")
        assertEquals(expectedUrl, result2)
        assertEquals(1, requestCount) // Request count should not increase
    }
}
