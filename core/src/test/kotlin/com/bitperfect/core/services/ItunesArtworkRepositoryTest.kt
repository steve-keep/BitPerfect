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

class ItunesArtworkRepositoryTest {

    private val mockContext = mockk<Context>(relaxed = true)

    @Test
    fun `fetchItunesArtwork returns previewUrl and highResUrl on success`() = runBlocking {
        val mockEngine = MockEngine { request ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/80/7e/ab/807eab7e-ccf8-3e91-cd20-4050d2bb2e40/12345.jpg/100x100bb.jpg"
                        }
                    ]
                }
            """.trimIndent()

            respond(
                content = jsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = ItunesArtworkRepository(mockContext, mockEngine)
        val result = repository.fetchItunesArtwork("Artist", "Album")

        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/80/7e/ab/807eab7e-ccf8-3e91-cd20-4050d2bb2e40/12345.jpg/600x600bb.jpg", result?.previewUrl)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/80/7e/ab/807eab7e-ccf8-3e91-cd20-4050d2bb2e40/12345.jpg/3000x3000bb.jpg", result?.highResUrl)
    }

    @Test
    fun `fetchItunesArtwork returns null when results is empty`() = runBlocking {
        val mockEngine = MockEngine { request ->
            val jsonResponse = """
                {
                    "resultCount": 0,
                    "results": []
                }
            """.trimIndent()

            respond(
                content = jsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = ItunesArtworkRepository(mockContext, mockEngine)
        val result = repository.fetchItunesArtwork("Artist", "Album")

        assertNull(result)
    }

    @Test
    fun `fetchItunesArtwork returns null when results lacks artworkUrl100`() = runBlocking {
        val mockEngine = MockEngine { request ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {}
                    ]
                }
            """.trimIndent()

            respond(
                content = jsonResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = ItunesArtworkRepository(mockContext, mockEngine)
        val result = repository.fetchItunesArtwork("Artist", "Album")

        assertNull(result)
    }

    @Test
    fun `fetchItunesArtwork returns null on error`() = runBlocking {
        val mockEngine = MockEngine { request ->
            respondError(HttpStatusCode.InternalServerError)
        }

        val repository = ItunesArtworkRepository(mockContext, mockEngine)
        val result = repository.fetchItunesArtwork("Artist", "Album")

        assertNull(result)
    }
}
