package com.bitperfect.core.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MusicBrainzRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun getSyntheticToc(): DiscToc {
        return DiscToc(
            tracks = listOf(
                TocEntry(trackNumber = 1, lba = 0),
                TocEntry(trackNumber = 2, lba = 16000),
                TocEntry(trackNumber = 3, lba = 32000)
            ),
            leadOutLba = 48000
        )
    }

    @Test
    fun `successful lookup returns correct DiscMetadata`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val mockJson = """
            {
                "releases": [
                    {
                        "id": "release-id-123",
                        "title": "Test Album",
                        "artist-credit": [
                            {
                                "artist": {
                                    "name": "Test Artist"
                                }
                            }
                        ],
                        "media": [
                            {
                                "position": 1,
                                "discs": [{"id": "wrong-disc-id"}],
                                "tracks": [
                                    { "title": "Wrong Track 1" }
                                ]
                            },
                            {
                                "position": 2,
                                "discs": [{"id": "$discId"}],
                                "tracks": [
                                    { "title": "Track 1" },
                                    { "title": "Track 2" }
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull(metadata)
        assertEquals("Test Album", metadata!!.albumTitle)
        assertEquals("Test Artist", metadata.artistName)
        assertEquals("release-id-123", metadata.mbReleaseId)
        assertEquals(listOf("Track 1", "Track 2"), metadata.trackTitles)
        assertEquals(2, metadata.discNumber)
        assertEquals(2, metadata.totalDiscs)
    }

    @Test
    fun `testLookupReturnsMetadataForFlattenedSingularResponse`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        // Mock a flattened response (e.g. The Bronx)
        val mockJson = """
            {
                "id": "flattened-id-123",
                "title": "The Bronx",
                "artist": "The Bronx",
                "track-count": 11,
                "tracks": [
                    {"title": "Track 1"},
                    {"title": "Track 2"},
                    {"title": "Track 3"},
                    {"title": "Track 4"},
                    {"title": "Track 5"},
                    {"title": "Track 6"},
                    {"title": "Track 7"},
                    {"title": "Track 8"},
                    {"title": "Track 9"},
                    {"title": "Track 10"},
                    {"title": "Track 11"}
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull(metadata)
        assertEquals("The Bronx", metadata!!.albumTitle)
        assertEquals("The Bronx", metadata.artistName)
        assertEquals("flattened-id-123", metadata.mbReleaseId)
        assertEquals(11, metadata.trackTitles.size)
        assertEquals("Track 1", metadata.trackTitles[0])
        assertEquals("Track 11", metadata.trackTitles[10])
        assertEquals(1, metadata.discNumber)
        assertEquals(1, metadata.totalDiscs)
        assertNull(metadata.year)
        assertNull(metadata.genre)
        assertEquals(emptyList<String>(), metadata.releaseTags)
        assertEquals(emptyList<List<String>>(), metadata.trackTags)
        assertEquals(false, metadata.hasFrontCoverArt)
    }

    @Test
    fun `empty releases list returns null`(): Unit = runBlocking {
        val mockJson = """
            {
                "releases": []
            }
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(getSyntheticToc())

        assertNull(metadata)
    }

    @Test
    fun `404 response returns null without throwing`(): Unit = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(getSyntheticToc())

        assertNull(metadata)
    }

    @Test
    fun `disc ID lookup with wrapped disc response returns correct DiscMetadata`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        // Simulates the real MB /ws/2/discid/{id} response shape:
        // releases are nested inside a "disc" object, NOT at the top level.
        val mockJson = """
            {
                "disc": {
                    "id": "$discId",
                    "releases": [
                        {
                            "id": "release-id-456",
                            "title": "Every Kingdom",
                            "date": "2011",
                            "artist-credit": [
                                {
                                    "artist": {
                                        "name": "Ben Howard"
                                    }
                                }
                            ],
                            "media": [
                                {
                                    "position": 1,
                                    "discs": [{"id": "$discId"}],
                                    "tracks": [
                                        { "title": "Old Pine" },
                                        { "title": "Promise" },
                                        { "title": "The Wolves" }
                                    ]
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull("Metadata should not be null for wrapped disc response", metadata)
        assertEquals("Every Kingdom", metadata!!.albumTitle)
        assertEquals("Ben Howard", metadata.artistName)
        assertEquals("release-id-456", metadata.mbReleaseId)
        assertEquals("2011", metadata.year)
        assertEquals(listOf("Old Pine", "Promise", "The Wolves"), metadata.trackTitles)
        assertEquals(1, metadata.discNumber)
    }

    @Test
    fun `disc ID lookup falls back to top-level releases when wrapped releases is empty`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        // Simulates an edge case where disc exists but has empty releases,
        // and top-level releases exist.
        val mockJson = """
            {
                "disc": {
                    "id": "$discId",
                    "releases": []
                },
                "releases": [
                    {
                        "id": "release-id-fallback",
                        "title": "Fallback Album",
                        "artist-credit": [
                            {
                                "artist": {
                                    "name": "Fallback Artist"
                                }
                            }
                        ],
                        "media": [
                            {
                                "position": 1,
                                "discs": [{"id": "$discId"}],
                                "tracks": [
                                    { "title": "Fallback Track 1" }
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull("Metadata should not be null for fallback response", metadata)
        assertEquals("Fallback Album", metadata!!.albumTitle)
        assertEquals("Fallback Artist", metadata.artistName)
        assertEquals("release-id-fallback", metadata.mbReleaseId)
        assertEquals(listOf("Fallback Track 1"), metadata.trackTitles)
    }

    @Test
    fun `lookup checks cache and parses successfully`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")
        val mockJson = """
            {
                "disc": {
                    "id": "some-disc-id",
                    "releases": [
                        {
                            "id": "release-id-cached",
                            "title": "Cached Album",
                            "artist-credit": [
                                {
                                    "artist": {
                                        "name": "Cached Artist"
                                    }
                                }
                            ],
                            "media": [
                                {
                                    "tracks": [
                                        { "title": "Cached Track 1" }
                                    ]
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()
        cacheFile.writeText(mockJson)

        val mockEngine = MockEngine { _ ->
            respond(
                content = "{}", // Should not hit network
                status = HttpStatusCode.NotFound
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull(metadata)
        assertEquals("Cached Album", metadata!!.albumTitle)
        assertEquals("Cached Artist", metadata.artistName)
        assertEquals("release-id-cached", metadata.mbReleaseId)
        assertEquals(listOf("Cached Track 1"), metadata.trackTitles)

        // Clean up
        cacheFile.delete()
    }

    @Test
    fun `lookup with network exception throws exception`(): Unit = runBlocking {
        val mockEngine = MockEngine { _ ->
            throw RuntimeException("Network error")
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        var exceptionThrown = false
        try {
            repository.lookup(getSyntheticToc())
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assert(exceptionThrown)
    }

    @Test
    fun `computeMusicBrainzDiscId is deterministic`() {
        val toc = getSyntheticToc()
        val id1 = computeMusicBrainzDiscId(toc)
        val id2 = computeMusicBrainzDiscId(toc)

        assertNotNull(id1)
        assertEquals(id1, id2)
        assert(id1.isNotEmpty())
    }

    @Test
    fun `lookup with malformed cache ignores cache and fetches from network`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")
        cacheFile.writeText("{ invalid json }")

        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"releases": []}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)
        assertNull(metadata) // Network responds with empty releases list
        cacheFile.delete()
    }

    @Test
    fun `lookup with expired cache fetches from network`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")
        cacheFile.writeText("""{"releases": []}""")
        // Expired by being older than 1 hour
        cacheFile.setLastModified(System.currentTimeMillis() - (60L * 60 * 1000 + 1000))

        val mockJson = """
            {
                "releases": [
                    {
                        "id": "release-id-123",
                        "title": "Network Album",
                        "artist-credit": [],
                        "media": []
                    }
                ]
            }
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)
        assertNotNull(metadata)
        assertEquals("Network Album", metadata!!.albumTitle)
        assertEquals("Unknown Artist", metadata.artistName) // tests empty artist-credit
        assertEquals(emptyList<String>(), metadata.trackTitles) // tests empty media

        cacheFile.delete()
    }

    @Test
    fun `disc ID lookup without cover art but with barcode performs TOC lookup and finds cover art`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val barcode = "123456789012"

        // Initial response: matches disc ID, has barcode, NO cover art
        val initialJson = """
            {
                "disc": {
                    "id": "$discId",
                    "releases": [
                        {
                            "id": "release-id-1",
                            "title": "Album Without Cover",
                            "barcode": "$barcode",
                            "cover-art-archive": {
                                "front": false
                            },
                            "artist-credit": [
                                {
                                    "artist": {
                                        "name": "Artist 1"
                                    }
                                }
                            ],
                            "media": [
                                {
                                    "position": 1,
                                    "discs": [{"id": "$discId"}],
                                    "tracks": [
                                        { "title": "Track 1" }
                                    ]
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        // Secondary response: TOC fuzzy lookup returns a release WITH the same barcode and WITH cover art
        val tocJson = """
            {
                "releases": [
                    {
                        "id": "release-id-2",
                        "title": "Album With Cover",
                        "barcode": "$barcode",
                        "cover-art-archive": {
                            "front": true
                        },
                        "artist-credit": [
                            {
                                "artist": {
                                    "name": "Artist 1"
                                }
                            }
                        ],
                        "media": [
                            {
                                "position": 1,
                                "tracks": [
                                    { "title": "Track 1" }
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            if (url.contains("discid/$discId")) {
                respond(
                    content = initialJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else if (url.contains("toc=") && url.contains("discid/-")) {
                respond(
                    content = tocJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = "", status = HttpStatusCode.NotFound)
            }
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)

        assertNotNull("Metadata should not be null", metadata)
        assertEquals("release-id-2", metadata!!.mbReleaseId)
        assertTrue("hasFrontCoverArt should be true from TOC fallback", metadata.hasFrontCoverArt)
        assertEquals("Album With Cover", metadata.albumTitle)
    }

    @Test
    fun `lookup handles cache write exception gracefully`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")

        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        cacheFile.mkdirs() // Create directory to force writeText to throw

        val mockJson = """
            {
                "releases": [
                    {
                        "id": "release-id-123",
                        "title": "Network Album",
                        "artist-credit": [],
                        "media": []
                    }
                ]
            }
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = mockJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val repository = MusicBrainzRepository(context, mockEngine)
        val metadata = repository.lookup(toc)
        assertNotNull(metadata)

        cacheFile.delete() // clean up the directory
    }
}
