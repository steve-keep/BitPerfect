package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReducedChunkRereadStrategyTest {

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
    fun `test chunk reduction depth 1`() = runBlocking {
        val chunk = createDummyChunk(100, 116) // 16 sectors
        val strategy = ReducedChunkRereadStrategy(currentDepth = 1)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(100, window.startLba)
        assertEquals(8, window.sectorCount)
    }

    @Test
    fun `test chunk reduction depth 2`() = runBlocking {
        val chunk = createDummyChunk(100, 116) // 16 sectors
        val strategy = ReducedChunkRereadStrategy(currentDepth = 2)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(100, window.startLba)
        assertEquals(8, window.sectorCount) // min limit
    }

    @Test
    fun `test chunk reduction lower limit enforcement`() = runBlocking {
        val policy = ChunkReductionPolicy(minimumChunkSize = 8)
        val chunk = createDummyChunk(100, 116) // 16 sectors
        val strategy = ReducedChunkRereadStrategy(policy = policy, currentDepth = 3)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(8, window.sectorCount) // Will not go below 8
    }
}
