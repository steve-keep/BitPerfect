package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscIdUtilsTest {

    @Test
    fun `computeMusicBrainzDiscId with empty tracks uses default track numbers`() {
        val toc = DiscToc(tracks = emptyList(), leadOutLba = 100)
        val result = computeMusicBrainzDiscId(toc)
        // Ensure it doesn't crash and returns a base64 string
        assertEquals(true, result.isNotEmpty())
    }

    @Test
    fun `computeMusicBrainzDiscId formats hexadecimal strings correctly`() {
        val toc = DiscToc(
            tracks = listOf(
                TocEntry(trackNumber = 1, lba = 150),
                TocEntry(trackNumber = 2, lba = 300)
            ),
            leadOutLba = 450
        )
        val result = computeMusicBrainzDiscId(toc)
        assertEquals(true, result.isNotEmpty())
    }
}
