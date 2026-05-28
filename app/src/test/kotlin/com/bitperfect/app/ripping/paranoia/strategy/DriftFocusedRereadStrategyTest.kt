package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DriftFocusedRereadStrategyTest {

    private fun createDummyChunk(startLba: Int, endLba: Int): VerifiedChunk {
        val sectors = endLba - startLba
        return VerifiedChunk(
            startLba = startLba,
            endLba = endLba,
            pcm = ByteArray(sectors * 2352),
            overlapHead = ByteArray(0),
            overlapTail = ByteArray(0),
            rereadCount = 0
        )
    }

    @Test
    fun `test drift focused strategy bounds`() = runBlocking {
        val chunk = createDummyChunk(100, 116) // 16 sectors
        val strategy = DriftFocusedRereadStrategy(overlapSizeSectors = 6)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(100, window.startLba)
        // 6 sectors + 2 = 8
        assertEquals(8, window.sectorCount)
    }

    @Test
    fun `test drift focused strategy respects max size`() = runBlocking {
        val chunk = createDummyChunk(100, 106) // 6 sectors
        val strategy = DriftFocusedRereadStrategy(overlapSizeSectors = 6)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(100, window.startLba)
        // Should not exceed chunk size
        assertEquals(6, window.sectorCount)
    }
}
