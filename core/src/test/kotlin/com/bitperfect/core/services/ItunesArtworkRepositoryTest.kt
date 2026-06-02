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
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "Artist",
                            "collectionName": "Album",
                            "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/80/7e/ab/807eab7e-ccf8-3e91-cd20-4050d2bb2e40/12345.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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

        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/80/7e/ab/807eab7e-ccf8-3e91-cd20-4050d2bb2e40/12345.jpg/3000x3000bb.jpg", result?.url)
    }

    @Test
    fun `fetchItunesArtwork ignores items with non-matching artist names`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 2,
                    "results": [
                        {
                            "artistName": "Tribute Fake",
                            "collectionName": "Album",
                            "artworkUrl100": "https://example.com/wrong.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
                        },
                        {
                            "artistName": "Artist",
                            "collectionName": "Album",
                            "artworkUrl100": "https://example.com/right.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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

        assertEquals("https://example.com/right.jpg/3000x3000bb.jpg", result?.url)
    }

    @Test
    fun `fetchItunesArtwork returns null when results is empty`() = runBlocking {
        val mockEngine = MockEngine { _ ->
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
        val mockEngine = MockEngine { _ ->
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
        val mockEngine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }

        val repository = ItunesArtworkRepository(mockContext, mockEngine)
        val result = repository.fetchItunesArtwork("Artist", "Album")

        assertNull(result)
    }

    @Test
    fun `fetchItunesArtwork handles apostrophe in artist name`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "Guns N’ Roses",
                            "collectionName": "Appetite for Destruction",
                            "artworkUrl100": "https://example.com/guns.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        val result = repository.fetchItunesArtwork("Guns N' Roses", "Appetite for Destruction")

        assertEquals("https://example.com/guns.jpg/3000x3000bb.jpg", result?.url)
    }

    @Test
    fun `fetchItunesArtwork handles parentheses in album title`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "Guns N' Roses",
                            "collectionName": "Use Your Illusion I",
                            "artworkUrl100": "https://example.com/illusion.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        val result = repository.fetchItunesArtwork("Guns N' Roses", "Use Your Illusion (I)")

        assertEquals("https://example.com/illusion.jpg/3000x3000bb.jpg", result?.url)
    }


    @Test
    fun `fetchItunesArtwork ignores prefix the in artist names`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "The Beatles",
                            "collectionName": "Abbey Road",
                            "artworkUrl100": "https://example.com/abbey.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        // Request without "The", should match the candidate with "The"
        val result = repository.fetchItunesArtwork("Beatles", "Abbey Road")

        assertEquals("https://example.com/abbey.jpg/3000x3000bb.jpg", result?.url)
    }

    @Test
    fun `fetchItunesArtwork rejects candidate if album matches but artist overlap is weak`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "Nine Inch Richards",
                            "collectionName": "The Fragile",
                            "artworkUrl100": "https://example.com/wrong.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        val result = repository.fetchItunesArtwork("Nine Inch Nails", "The Fragile")

        assertNull(result)
    }

    @Test
    fun `fetchItunesArtwork prefers exact match over anniversary edition and remix`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 3,
                    "results": [
                        {
                            "artistName": "Nas",
                            "collectionName": "From Illmatic To Stillmatic: The Remixes",
                            "trackCount": 14,
                            "artworkUrl100": "https://example.com/remix.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
                        },
                        {
                            "artistName": "Nas",
                            "collectionName": "Illmatic (10th Anniversary Platinum Edition)",
                            "trackCount": 16,
                            "artworkUrl100": "https://example.com/anniversary.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
                        },
                        {
                            "artistName": "Nas",
                            "collectionName": "Illmatic",
                            "trackCount": 10,
                            "artworkUrl100": "https://example.com/exact.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        val result = repository.fetchItunesArtwork("Nas", "Illmatic", 10)

        assertEquals("https://example.com/exact.jpg/3000x3000bb.jpg", result?.url)
    }

    @Test
    fun `fetchItunesArtwork handles bonus track version variant`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            val jsonResponse = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "artistName": "The Kooks",
                            "collectionName": "Inside In / Inside Out (Bonus Track Version)",
                            "artworkUrl100": "https://example.com/inside.jpg/100x100bb.jpg",
                            "wrapperType": "collection",
                            "collectionType": "Album"
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
        val result = repository.fetchItunesArtwork("The Kooks", "Inside In / Inside Out")

        assertEquals("https://example.com/inside.jpg/3000x3000bb.jpg", result?.url)
    }
}
