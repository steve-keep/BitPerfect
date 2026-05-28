package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.OverlapVerifier
import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryContext

class FullChunkRecoveryStrategyTest {

    private val sectorSize = 2352
    private val overlapSizeSectors = 6
    private val verifier = OverlapVerifier(overlapSizeSectors)
    private val strategy = FullChunkRecoveryStrategy()

    private fun createChunk(lba: Int, lengthSectors: Int, pcmFillByte: Byte): VerifiedChunk {
        val pcm = ByteArray(lengthSectors * sectorSize) { pcmFillByte }
        return VerifiedChunk(
            startLba = lba,
            endLba = lba + lengthSectors,
            pcm = pcm,
            overlapHead = verifier.extractOverlapHead(pcm),
            overlapTail = verifier.extractOverlapTail(pcm),
            rereadCount = 0
        )
    }

    @Test
    fun `getRecoveryWindow calculates correct bounds`() {
        val chunk = createChunk(100, 16, 0)
        val window = strategy.getRecoveryWindow(chunk)

        assertEquals(100, window.startLba)
        assertEquals(16, window.sectorCount)
    }

    @Test
    fun `performAttempt replaces full chunk and preserves alignment`() = runBlocking {
        // Original 16 sector chunk filled with 0s
        val failedChunk = createChunk(100, 16, 0)

        val result = strategy.performAttempt(RecoveryContext(0, 0, 0, 0, null, null, RipConfidence.HIGH), failedChunk) { lba, sectors ->
            assertEquals(100, lba)
            assertEquals(16, sectors)
            createChunk(lba, sectors, 1)
        }

        assertNotNull(result)

        // Check alignment: length should be exactly original chunk length
        assertEquals(16 * sectorSize, result!!.pcm.size)

        // The entire chunk should be 1s (recovered)
        val expectedPcm = ByteArray(16 * sectorSize) { 1 }
        assertArrayEquals(expectedPcm, result.pcm)
    }

    @Test
    fun `performAttempt handles null read gracefully`() = runBlocking {
        val failedChunk = createChunk(100, 16, 0)
        val result = strategy.performAttempt(RecoveryContext(0, 0, 0, 0, null, null, RipConfidence.HIGH), failedChunk) { _, _ -> null }
        assertNull(result)
    }
}
