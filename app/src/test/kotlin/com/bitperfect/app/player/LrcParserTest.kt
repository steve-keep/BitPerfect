package com.bitperfect.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesStandardLines() {
        val lrc = """
            [00:12.34] First line of lyrics
            [00:15.00] Second line
            [01:02.50] Another line
        """.trimIndent()

        val expected = listOf(
            LrcLine(12340, "First line of lyrics"),
            LrcLine(15000, "Second line"),
            LrcLine(62500, "Another line")
        )

        assertEquals(expected, parseLrc(lrc))
    }

    @Test
    fun skipsBlankAndMetadataLines() {
        val lrc = """
            [ar:Artist]
            [ti:Title]
            [00:10.00]
            [00:12.34] Valid line

            [00:15.00]
        """.trimIndent()

        val expected = listOf(
            LrcLine(12340, "Valid line")
        )

        assertEquals(expected, parseLrc(lrc))
    }

    @Test
    fun returnsEmptyListForEmptyInput() {
        assertTrue(parseLrc("").isEmpty())
        assertTrue(parseLrc("   \n  ").isEmpty())
    }

    @Test
    fun sortsByTimestamp() {
        val lrc = """
            [01:00.00] Last
            [00:10.00] First
            [00:30.00] Middle
        """.trimIndent()

        val expected = listOf(
            LrcLine(10000, "First"),
            LrcLine(30000, "Middle"),
            LrcLine(60000, "Last")
        )

        assertEquals(expected, parseLrc(lrc))
    }
}
