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
    fun `lookup checks cache and parses successfully`(): Unit = runBlocking {
        val toc = getSyntheticToc()
        val discId = computeMusicBrainzDiscId(toc)
        val cacheFile = File(context.cacheDir, "mb_$discId.json")
        val mockJson = """
            {
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
