package com.bitperfect.core.engine

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import android.util.Log
import io.mockk.*
import java.lang.reflect.Field

class MetadataServiceTest {

    private lateinit var metadataService: MetadataService
    private var callCount = 0

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any<String>()) } returns 0

        val mockEngine = MockEngine { request ->
            callCount++
            respond(
                content = """
                {
                  "releases": [
                    {
                      "id": "release-id-1",
                      "title": "Test Album",
                      "date": "1999-10-10",
                      "country": "US",
                      "artist-credit": [
                        { "name": "Test Artist" }
                      ],
                      "media": [
                        {
                          "tracks": [
                            { "title": "Track 1" },
                            { "title": "Track 2" }
                          ]
                        }
                      ]
                    },
                    {
                      "id": "release-id-2",
                      "title": "Test Album (Japan)",
                      "date": "2000-01-01",
                      "country": "JP",
                      "artist-credit": [
                        { "name": "Test Artist" }
                      ],
                      "media": [
                        {
                          "tracks": [
                            { "title": "Track 1" },
                            { "title": "Track 2 (Bonus)" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }

        metadataService = MetadataService()

        // Use reflection to inject the mock client
        val clientField: Field = MetadataService::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(metadataService, client)
    }

    @org.junit.After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testFetchMetadata_ParsesMultipleReleases() = runBlocking {
        val toc = DiscToc(1, 2, listOf(
            TrackEntry(1, 0, 15000, true),
            TrackEntry(2, 15000, 15000, true)
        ), 30000, 30000)

        val result = metadataService.fetchMetadata("dummy-disc-id", toc, null)

        assertEquals(2, result.size)

        val first = result[0]
        assertEquals("release-id-1", first.id)
        assertEquals("Test Album", first.album)
        assertEquals("Test Artist", first.artist)
        assertEquals("1999", first.year)
        assertEquals("US", first.country)
        assertEquals(2, first.tracks.size)
        assertEquals("Track 1", first.tracks[0])
        assertEquals("Track 2", first.tracks[1])
        assertEquals("https://coverartarchive.org/release/release-id-1/front-250", first.albumArtUrl)

        val second = result[1]
        assertEquals("release-id-2", second.id)
        assertEquals("Test Album (Japan)", second.album)
        assertEquals("JP", second.country)
        assertEquals("2000", second.year)
        assertEquals("Track 2 (Bonus)", second.tracks[1])

        assertEquals(1, callCount)
    }

    @Test
    fun testFetchMetadata_UsesSessionCache() = runBlocking {
        val toc = DiscToc(1, 2, listOf(
            TrackEntry(1, 0, 15000, true),
            TrackEntry(2, 15000, 15000, true)
        ), 30000, 30000)

        val result1 = metadataService.fetchMetadata("dummy-disc-id", toc, null)
        assertEquals(2, result1.size)
        assertEquals(1, callCount)

        val result2 = metadataService.fetchMetadata("dummy-disc-id", toc, null)
        assertEquals(2, result2.size)
        assertEquals(1, callCount) // Call count should not increase
    }

    @Test
    fun testFetchMetadata_GnuDbFallback() = runBlocking {
        val mockEngine = MockEngine { request ->
            callCount++
            when (request.url.toString()) {
                "https://musicbrainz.org/ws/2/discid/dummy-disc-id-fallback?fmt=json&inc=recordings%2Bartists%2Brelease-groups%2Blabels" -> {
                    respond(
                        content = """{"releases": []}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=cddb+query+02019002+2+150+15150+400&hello=anon+localhost+BitPerfect+1.0&proto=6" -> {
                    respond(
                        content = "200 rock 12345678 Album Title\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain")
                    )
                }
                "https://gnudb.gnudb.org/~cddb/cddb.cgi?cmd=cddb+read+rock+12345678&hello=anon+localhost+BitPerfect+1.0&proto=6" -> {
                    respond(
                        content = """
                            210 rock 12345678 CD database entry follows
                            DTITLE=Fallback Artist / Fallback Album
                            DYEAR=2023
                            TTITLE0=Track 1 Title
                            TTITLE1=Track 2 Title
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/plain")
                    )
                }
                else -> {
                    respond(
                        content = "404 Not Found",
                        status = HttpStatusCode.NotFound
                    )
                }
            }
        }

        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }

        val service = MetadataService()
        val clientField: Field = MetadataService::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(service, client)

        val toc = DiscToc(1, 2, listOf(
            TrackEntry(1, 0, 15000, true),
            TrackEntry(2, 15000, 15000, true)
        ), 30000, 30000)

        val result = service.fetchMetadata("dummy-disc-id-fallback", toc, null)

        assertEquals(1, result.size)
        val album = result[0]
        assertEquals("Fallback Artist", album.artist)
        assertEquals("Fallback Album", album.album)
        assertEquals("2023", album.year)
        assertEquals("GnuDB", album.source)
        assertEquals(2, album.tracks.size)
        assertEquals("Track 1 Title", album.tracks[0])
        assertEquals("Track 2 Title", album.tracks[1])
    }
}
