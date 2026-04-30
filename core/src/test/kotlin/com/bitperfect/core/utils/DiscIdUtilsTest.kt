package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Test

class DiscIdUtilsTest {

    @Test
    fun computeAccurateRipDiscId_appetiteForDestruction_printsIds() {
        val tracks = listOf(
            TocEntry(trackNumber = 1, lba = 150),
            TocEntry(trackNumber = 2, lba = 18051),
            TocEntry(trackNumber = 3, lba = 32000),
            TocEntry(trackNumber = 4, lba = 47950),
            TocEntry(trackNumber = 5, lba = 63263),
            TocEntry(trackNumber = 6, lba = 80463),
            TocEntry(trackNumber = 7, lba = 95823),
            TocEntry(trackNumber = 8, lba = 112375),
            TocEntry(trackNumber = 9, lba = 130020),
            TocEntry(trackNumber = 10, lba = 149610),
            TocEntry(trackNumber = 11, lba = 165862),
            TocEntry(trackNumber = 12, lba = 181855)
        )
        val toc = DiscToc(
            tracks = tracks,
            leadOutLba = 197218
        )

        val ids = computeAccurateRipDiscId(toc)

        println("Appetite for Destruction AccurateRip IDs:")
        println("id1: ${String.format("%08x", ids.id1)}")
        println("id2: ${String.format("%08x", ids.id2)}")
        println("id3: ${String.format("%08x", ids.id3)}")
        // We will assert these values once they are verified against AccurateRip
    }
}
