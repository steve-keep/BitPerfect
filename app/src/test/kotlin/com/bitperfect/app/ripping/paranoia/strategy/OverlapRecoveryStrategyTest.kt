package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.OverlapVerifier
import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OverlapRecoveryStrategyTest {

    private val sectorSize = 2352
    private val overlapSizeSectors = 6
    private val overlapSizeBytes = overlapSizeSectors * sectorSize
    private val verifier = OverlapVerifier(overlapSizeSectors)
    private val strategy = OverlapRecoveryStrategy(verifier)

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
        assertEquals(6, window.sectorCount)
    }

    @Test
    fun `performAttempt replaces only overlap region and preserves exact alignment`() = runBlocking {
        // Original 16 sector chunk filled with 0s
        val failedChunk = createChunk(100, 16, 0)

        // Read attempt for the 6 overlap sectors filled with 1s
        val attemptPcm = ByteArray(overlapSizeBytes) { 1 }

        val result = strategy.performAttempt(failedChunk) { lba, sectors ->
            assertEquals(100, lba)
            assertEquals(6, sectors)
            createChunk(lba, sectors, 1)
        }

        assertNotNull(result)

        // Check alignment: length should be exactly original chunk length
        assertEquals(16 * sectorSize, result!!.pcm.size)

        // The first 6 sectors should be 1s (recovered overlap)
        val expectedHead = attemptPcm
        val actualHead = result!!.pcm.copyOfRange(0, 6 * sectorSize)
        assertArrayEquals(expectedHead, actualHead)

        // The remaining 10 sectors should be 0s (original chunk)
        val expectedTail = ByteArray(10 * sectorSize) { 0 }
        val actualTail = result!!.pcm.copyOfRange(6 * sectorSize, 16 * sectorSize)
        assertArrayEquals(expectedTail, actualTail)

        // Assert overlap head/tail match correctly
        assertArrayEquals(verifier.extractOverlapHead(result!!.pcm), result!!.overlapHead)
        assertArrayEquals(verifier.extractOverlapTail(result!!.pcm), result!!.overlapTail)
    }

    @Test
    fun `performAttempt handles null read gracefully`() = runBlocking {
        val failedChunk = createChunk(100, 16, 0)
        val result = strategy.performAttempt(failedChunk) { _, _ -> null }
        assertNull(result)
    }

    @Test
    fun `performAttempt handles short read gracefully`() = runBlocking {
        val failedChunk = createChunk(100, 16, 0)
        val result = strategy.performAttempt(failedChunk) { lba, _ ->
            createChunk(lba, 5, 1) // 5 sectors instead of 6
        }
        assertNull(result)
    }
}
