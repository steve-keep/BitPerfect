package com.bitperfect.app.library

import android.content.Context
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Candidate
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.resume
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE)
class AiMixGeneratorTest {

    private lateinit var context: Context
    private lateinit var model: GenerativeModel

    @Before
    fun setup() {
        context = mockk<Context>(relaxed = true)
        model = mockk<GenerativeModel>(relaxed = true)
        mockkObject(Generation)
        every { Generation.getClient() } returns model
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun testIsAvailableReturnsTrueWhenModelCreatedSuccessfully() = runTest {
        val generator = AiMixGenerator()
        val isAvail = generator.isAvailable(context)
        assertTrue(isAvail)
        verify { model.close() }
    }

    @Test
    fun testIsAvailableReturnsFalseWhenExceptionThrown() = runTest {
        every { Generation.getClient() } throws RuntimeException("Not supported")
        val generator = AiMixGenerator()
        val isAvail = generator.isAvailable(context)
        assertFalse(isAvail)
    }

    @Test
    fun testGenerateMixesParsesJsonCorrectly() = runTest {
        val jsonResponse = """
            [
              {
                "name": "Chill Mix",
                "description": "A relaxing mix",
                "trackTitles": ["Track A", "Track B"]
              }
            ]
        """.trimIndent()

        val candidate = mockk<Candidate>()
        every { candidate.text } returns jsonResponse

        val response = mockk<GenerateContentResponse>()
        every { response.candidates } returns listOf(candidate)

        coEvery { model.generateContent(any<String>()) } returns response

        val availableTracks = listOf(
            AiMixTrack(1L, "Artist 1", "Track A", "Album 1", 100L),
            AiMixTrack(2L, "Artist 2", "Track B", "Album 2", 101L),
            AiMixTrack(3L, "Artist 3", "Track C", "Album 3", 102L)
        )

        val generator = AiMixGenerator()
        val mixes = generator.generateMixes(context, "Library Summary", availableTracks)

        assertEquals(1, mixes.size)
        assertEquals("Chill Mix", mixes[0].name)
        assertEquals("A relaxing mix", mixes[0].description)
        assertEquals(2, mixes[0].tracks.size)
        assertEquals("Track A", mixes[0].tracks[0].title)
        assertEquals("Track B", mixes[0].tracks[1].title)

        verify { model.close() }
    }

    @Test
    fun testGenerateMixesReturnsEmptyListOnFailure() = runTest {
        coEvery { model.generateContent(any<String>()) } throws RuntimeException("Error")

        val generator = AiMixGenerator()

        val mixes = generator.generateMixes(context, "Library Summary", emptyList())
        assertTrue(mixes.isEmpty())
    }
}
