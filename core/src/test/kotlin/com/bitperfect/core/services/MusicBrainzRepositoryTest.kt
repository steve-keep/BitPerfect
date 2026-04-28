package com.bitperfect.core.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MusicBrainzRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean cache dir before tests
        context.cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun createMockRepository(responseJson: String, status: HttpStatusCode = HttpStatusCode.OK): MusicBrainzRepository {
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(responseJson),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return MusicBrainzRepository(context, mockEngine)
    }

    @Test
    fun `successful lookup returns correct DiscMetadata`() = runTest {
        val json = """
            {
              "releases": [
                {
                  "id": "release-123",
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
        val repo = createMockRepository(json)

        val toc = DiscToc(listOf(TocEntry(1, 0), TocEntry(2, 100)), 200)
        val metadata = repo.lookup(toc)

        assertEquals("Test Album", metadata?.albumTitle)
        assertEquals("Test Artist", metadata?.artistName)
        assertEquals(listOf("Track 1", "Track 2"), metadata?.trackTitles)
    }

    @Test
    fun `empty releases returns null`() = runTest {
        val json = """
            {
              "releases": []
            }
        """.trimIndent()
        val repo = createMockRepository(json)

        val toc = DiscToc(listOf(TocEntry(1, 0), TocEntry(2, 100)), 200)
        val metadata = repo.lookup(toc)

        assertNull(metadata)
    }

    @Test
    fun `404 response returns null without throwing`() = runTest {
        val repo = createMockRepository("Not Found", HttpStatusCode.NotFound)

        val toc = DiscToc(listOf(TocEntry(1, 0), TocEntry(2, 100)), 200)
        val metadata = repo.lookup(toc)

        assertNull(metadata)
    }

    @Test
    fun `computeMusicBrainzDiscId is deterministic`() {
        // Track 1 LBA 0, Track 2 LBA 16000, Track 3 LBA 32000, lead-out LBA 48000
        val toc = DiscToc(
            tracks = listOf(
                TocEntry(1, 0),
                TocEntry(2, 16000),
                TocEntry(3, 32000)
            ),
            leadOutLba = 48000
        )

        val id1 = computeMusicBrainzDiscId(toc)
        val id2 = computeMusicBrainzDiscId(toc)

        assertTrue(id1.isNotEmpty())
        assertEquals(id1, id2)
    }
}
