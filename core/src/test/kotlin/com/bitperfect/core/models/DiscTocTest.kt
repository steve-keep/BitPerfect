package com.bitperfect.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscTocTest {

    @Test
    fun `audioTrackCount computes correctly`() {
        val tracks = listOf(
            TrackInfo(1, 150, true),
            TrackInfo(2, 5000, true),
            TrackInfo(3, 10000, false), // data track
            TrackInfo(4, 15000, true)
        )

        val toc = DiscToc(
            firstTrack = 1,
            lastTrack = 4,
            tracks = tracks,
            leadOutLba = 20000
        )

        assertEquals(3, toc.audioTrackCount)
        assertEquals(4, toc.tracks.size)
        assertEquals(20000, toc.leadOutLba)
    }
}
