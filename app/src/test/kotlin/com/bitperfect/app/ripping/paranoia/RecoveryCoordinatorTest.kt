package com.bitperfect.app.ripping.paranoia

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.random.Random
import kotlin.math.min
import kotlin.math.max

class RecoveryCoordinatorTest {

    private lateinit var verifier: OverlapVerifier
    private lateinit var engine: RereadEngine
    private lateinit var coordinator: RecoveryCoordinator

    @Before
    fun setup() {
        verifier = OverlapVerifier(overlapSizeSectors = 2)
        engine = RereadEngine(verifier = verifier, maxRereads = 3)
        coordinator = RecoveryCoordinator(rereadEngine = engine, verifier = verifier)
    }

    private fun createDummyChunk(startLba: Int, overlapSizeSectors: Int, isError: Boolean = false, fixedNoise: Byte? = null): VerifiedChunk {
        val sectors = 16
        val pcmSize = sectors * 2352
        val pcm = ByteArray(pcmSize)
        if (!isError) {
            for (i in pcm.indices) {
                pcm[i] = ((startLba * 2352 + i) % 256).toByte()
            }
        } else if (fixedNoise != null) {
            for (i in pcm.indices) {
                pcm[i] = fixedNoise
            }
        } else {
            Random.nextBytes(pcm)
        }

        val overlapBytes = overlapSizeSectors * 2352
        val overlapHead = pcm.copyOfRange(0, overlapBytes)
        val overlapTail = pcm.copyOfRange(pcm.size - overlapBytes, pcm.size)

        return VerifiedChunk(
            startLba = startLba,
            endLba = startLba + sectors,
            pcm = pcm,
            overlapHead = overlapHead,
            overlapTail = overlapTail,
            rereadCount = 0
        )
    }

    @Test
    fun `test successful recovery via initial overlap strategy`() = runBlocking {
        val previousChunk = createDummyChunk(100, 2)
        val failedChunk = createDummyChunk(114, 2, isError = true)
        val correctChunk = createDummyChunk(114, 2)

        val result = coordinator.recover(
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk
        ) { lba, sectors ->
            val startOffset = (lba - 114) * 2352
            val size = sectors * 2352
            val pcm = correctChunk.pcm.copyOfRange(startOffset, startOffset + size)
            VerifiedChunk(
                startLba = lba,
                endLba = lba + sectors,
                pcm = pcm,
                overlapHead = verifier.extractOverlapHead(pcm),
                overlapTail = verifier.extractOverlapTail(pcm),
                rereadCount = 0
            )
        }
        assertTrue(result is RereadRecoveryResult.Recovered)
        val recResult = result as RereadRecoveryResult.Recovered
        assertEquals(1, recResult.metadataHistory.size)
        assertEquals("overlap_recovery", recResult.metadataHistory.first().strategy)
    }

    @Test
    fun `test persistent instability escalation to reduced chunk`() = runBlocking {
        val previousChunk = createDummyChunk(100, 2)
        val failedChunk = createDummyChunk(114, 2, isError = true)
        val correctChunk = createDummyChunk(114, 2)

        var calls = 0
        val result = coordinator.recover(previousChunk, failedChunk) { lba, sectors ->
            calls++
            if (calls <= 3) {
                // Random noise -> PERSISTENT_INSTABILITY -> ReducedChunk
                val chunk = createDummyChunk(lba, 2, isError = true)
                val startOffset = (lba - 114) * 2352
                val size = sectors * 2352
                val pcm = chunk.pcm.copyOfRange(startOffset, startOffset + size)
                VerifiedChunk(
                    startLba = lba,
                    endLba = lba + sectors,
                    pcm = pcm,
                    overlapHead = verifier.extractOverlapHead(pcm),
                    overlapTail = verifier.extractOverlapTail(pcm),
                    rereadCount = 0
                )
            } else {
                // Second strategy kicks in (ReducedChunk)
                val startOffset = (lba - 114) * 2352
                val size = sectors * 2352
                val pcm = correctChunk.pcm.copyOfRange(startOffset, startOffset + size)
                VerifiedChunk(
                    startLba = lba,
                    endLba = lba + sectors,
                    pcm = pcm,
                    overlapHead = verifier.extractOverlapHead(pcm),
                    overlapTail = verifier.extractOverlapTail(pcm),
                    rereadCount = 0
                )
            }
        }

        assertTrue(result is RereadRecoveryResult.Recovered)
        val recResult = result as RereadRecoveryResult.Recovered
        assertEquals(2, recResult.metadataHistory.size)
        assertEquals("overlap_recovery", recResult.metadataHistory[0].strategy)
        assertEquals("reduced_chunk_recovery", recResult.metadataHistory[1].strategy)
    }

    @Test
    fun `test drift escalation`() = runBlocking {
        val previousChunk = createDummyChunk(100, 2)
        val correctChunk = createDummyChunk(114, 2)

        // Shift failed chunk by a small amount to trigger drift
        val shiftedPcm = ByteArray(correctChunk.pcm.size)
        val shift = 10
        System.arraycopy(correctChunk.pcm, shift, shiftedPcm, 0, shiftedPcm.size - shift)
        val failedChunk = VerifiedChunk(
            startLba = 114,
            endLba = 114 + 16,
            pcm = shiftedPcm,
            overlapHead = verifier.extractOverlapHead(shiftedPcm),
            overlapTail = verifier.extractOverlapTail(shiftedPcm),
            rereadCount = 0
        )

        var calls = 0
        val result = coordinator.recover(previousChunk, failedChunk) { lba, sectors ->
            calls++
            if (calls <= 3) {
                // Return stable shifted data, drift detector should catch this
                val startOffset = (lba - 114) * 2352
                val size = sectors * 2352
                val pcm = shiftedPcm.copyOfRange(startOffset, startOffset + size)
                VerifiedChunk(
                    startLba = lba,
                    endLba = lba + sectors,
                    pcm = pcm,
                    overlapHead = verifier.extractOverlapHead(pcm),
                    overlapTail = verifier.extractOverlapTail(pcm),
                    rereadCount = 0
                )
            } else {
                // Drift strategy activated, return correct data to finish
                val startOffset = (lba - 114) * 2352
                val size = sectors * 2352
                val pcm = correctChunk.pcm.copyOfRange(startOffset, startOffset + size)
                VerifiedChunk(
                    startLba = lba,
                    endLba = lba + sectors,
                    pcm = pcm,
                    overlapHead = verifier.extractOverlapHead(pcm),
                    overlapTail = verifier.extractOverlapTail(pcm),
                    rereadCount = 0
                )
            }
        }

        assertTrue(result is RereadRecoveryResult.Recovered)
        val recResult = result as RereadRecoveryResult.Recovered
        assertEquals(2, recResult.metadataHistory.size)
        assertEquals("overlap_recovery", recResult.metadataHistory[0].strategy)
        assertEquals("drift_focused_recovery", recResult.metadataHistory[1].strategy)
    }

    @Test
    fun `test escalation depth limit aborts`() = runBlocking {
        val previousChunk = createDummyChunk(100, 2)
        val failedChunk = createDummyChunk(114, 2, isError = true)

        val result = coordinator.recover(previousChunk, failedChunk) { lba, sectors ->
            // Always return noise to fail every strategy
            val chunk = createDummyChunk(lba, 2, isError = true)
            val startOffset = (lba - 114) * 2352
            val size = sectors * 2352
            val pcm = chunk.pcm.copyOfRange(startOffset, startOffset + size)
            VerifiedChunk(
                startLba = lba,
                endLba = lba + sectors,
                pcm = pcm,
                overlapHead = verifier.extractOverlapHead(pcm),
                overlapTail = verifier.extractOverlapTail(pcm),
                rereadCount = 0
            )
        }

        assertTrue(result is RereadRecoveryResult.Failed)
        val failResult = result as RereadRecoveryResult.Failed
        // It should try up to maxStrategyTransitions (4) strategies
        assertEquals(4, failResult.metadataHistory.size)
        assertEquals("overlap_recovery", failResult.metadataHistory[0].strategy)
        // With random noise, the next three will all be reduced chunk
        assertEquals("reduced_chunk_recovery", failResult.metadataHistory[1].strategy)
        assertEquals("reduced_chunk_recovery", failResult.metadataHistory[2].strategy)
        assertEquals("reduced_chunk_recovery", failResult.metadataHistory[3].strategy)
    }
}
