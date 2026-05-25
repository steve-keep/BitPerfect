package com.bitperfect.app.ripping.paranoia

import kotlinx.coroutines.runBlocking
import com.bitperfect.app.ripping.paranoia.strategy.OverlapRecoveryStrategy
import com.bitperfect.app.ripping.paranoia.strategy.FullChunkRecoveryStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RereadEngineTest {

    private val sectorSize = 2352

    private fun createChunk(lba: Int, lengthSectors: Int, overlapHeadBytes: ByteArray, overlapTailBytes: ByteArray, pcmFillByte: Byte): VerifiedChunk {
        val pcm = ByteArray(lengthSectors * sectorSize) { pcmFillByte }
        return VerifiedChunk(
            startLba = lba,
            endLba = lba + lengthSectors,
            pcm = pcm,
            overlapHead = overlapHeadBytes,
            overlapTail = overlapTailBytes,
            rereadCount = 0
        )
    }

    @Test
    fun testStableRereadsRecoverEarly() = runBlocking {
        val verifier = OverlapVerifier(overlapSizeSectors = 1)
        val strategies = listOf(
            OverlapRecoveryStrategy(verifier),
            FullChunkRecoveryStrategy()
        )
        val engine = RereadEngine(strategies, verifier, maxRereads = 6)

        val previousTail = ByteArray(sectorSize) { 0 }

        val prevChunk = createChunk(
            lba = 0, lengthSectors = 2,
            overlapHeadBytes = ByteArray(sectorSize) { 0 },
            overlapTailBytes = previousTail,
            pcmFillByte = 0
        )

        val failedChunk = createChunk(
            lba = 2, lengthSectors = 2,
            overlapHeadBytes = ByteArray(sectorSize) { 9 }, // mismatched head
            overlapTailBytes = ByteArray(sectorSize) { 9 },
            pcmFillByte = 9
        )

        // Simulating Attempt 1: mismatched head, diff pcm
        val attempt1Pcm = ByteArray(sectorSize * 2) { 1 }

        // Simulating Attempt 2: matched head, diff pcm, NOT stable yet (doesn't match Attempt 1)
        val attempt2Pcm = ByteArray(sectorSize * 2) { 2 }
        System.arraycopy(previousTail, 0, attempt2Pcm, 0, sectorSize)

        // Simulating Attempt 3: matched head, matches attempt 2 pcm (STABLE)
        val attempt3Pcm = attempt2Pcm.clone()

        var readCount = 0
        val result = engine.recover(
            previousVerifiedChunk = prevChunk,
            failedChunk = failedChunk
        ) { lba, count ->
            readCount++
            when (readCount) {
                // Overlap Strategy reads only the overlap (1 sector)
                1 -> VerifiedChunk(lba, lba + count, attempt1Pcm.copyOfRange(0, sectorSize), verifier.extractOverlapHead(attempt1Pcm.copyOfRange(0, sectorSize)), verifier.extractOverlapTail(attempt1Pcm.copyOfRange(0, sectorSize)), readCount)
                2 -> VerifiedChunk(lba, lba + count, attempt2Pcm.copyOfRange(0, sectorSize), verifier.extractOverlapHead(attempt2Pcm.copyOfRange(0, sectorSize)), verifier.extractOverlapTail(attempt2Pcm.copyOfRange(0, sectorSize)), readCount)
                3 -> VerifiedChunk(lba, lba + count, attempt3Pcm.copyOfRange(0, sectorSize), verifier.extractOverlapHead(attempt3Pcm.copyOfRange(0, sectorSize)), verifier.extractOverlapTail(attempt3Pcm.copyOfRange(0, sectorSize)), readCount)
                // If it falls back to full chunk
                4 -> VerifiedChunk(lba, lba + count, attempt1Pcm, verifier.extractOverlapHead(attempt1Pcm), verifier.extractOverlapTail(attempt1Pcm), readCount)
                5 -> VerifiedChunk(lba, lba + count, attempt2Pcm, verifier.extractOverlapHead(attempt2Pcm), verifier.extractOverlapTail(attempt2Pcm), readCount)
                6 -> VerifiedChunk(lba, lba + count, attempt3Pcm, verifier.extractOverlapHead(attempt3Pcm), verifier.extractOverlapTail(attempt3Pcm), readCount)

                else -> null
            }
        }

        assertTrue("Expected Recovered but was: $result", result is RereadRecoveryResult.Recovered)
        val recoveredChunk = (result as RereadRecoveryResult.Recovered).chunk
        assertEquals(3, readCount)
        assertEquals(3, recoveredChunk.rereadCount)
    }

    @Test
    fun testMaxRetriesEnforcedAndReturnsFailed() = runBlocking {
        val verifier = OverlapVerifier(overlapSizeSectors = 1)
        val strategies = listOf(
            OverlapRecoveryStrategy(verifier),
            FullChunkRecoveryStrategy()
        )
        val engine = RereadEngine(strategies, verifier, maxRereads = 3)

        val previousTail = ByteArray(sectorSize) { 0 }
        val prevChunk = createChunk(0, 2, ByteArray(sectorSize) { 0 }, previousTail, 0)
        val failedChunk = createChunk(2, 2, ByteArray(sectorSize) { 9 }, ByteArray(sectorSize) { 9 }, 9)

        var readCount = 0
        val result = engine.recover(prevChunk, failedChunk) { lba, count ->
            readCount++
            val pcm = ByteArray(sectorSize * count) { readCount.toByte() } // Never matches previous attempt
            VerifiedChunk(lba, lba + count, pcm, verifier.extractOverlapHead(pcm), verifier.extractOverlapTail(pcm), readCount)
        }

        assertTrue(result is RereadRecoveryResult.Failed)
        val failedResultChunk = (result as RereadRecoveryResult.Failed).chunk
        assertEquals(6, readCount) // 3 retries for Overlap, 3 for FullChunk
        assertEquals(3, failedResultChunk.rereadCount) // Metadata history tracks retries per strategy, but maxRereads is the bound
        assertEquals(6.toByte(), failedResultChunk.pcm[0]) // Selects latest reread candidate from the last strategy
    }
}
