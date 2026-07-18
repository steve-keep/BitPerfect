package com.bitperfect.app.ripping.paranoia

import com.bitperfect.app.ripping.paranoia.strategy.RecoveryContext
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryStrategy
import com.bitperfect.app.ripping.paranoia.strategy.RecoveryWindow
import com.bitperfect.core.utils.MonotonicClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RereadEngineTest {

    private lateinit var verifier: OverlapVerifier
    private lateinit var clock: MonotonicClock
    private lateinit var strategy: RecoveryStrategy
    private lateinit var engine: RereadEngine

    private val maxRereads = 3
    private val chunkStartLba = 100
    private val chunkEndLba = 110

    private val defaultContext = RecoveryContext(
        trackNumber = 1,
        chunkStartLba = chunkStartLba,
        chunkEndLba = chunkEndLba,
        rereadAttempt = 0,
        candidateHistory = null,
        driftEvent = null,
        previousConfidence = RipConfidence.HIGH
    )

    private val readChunkDummy: suspend (lba: Int, sectors: Int) -> VerifiedChunk? = { _, _ -> null }

    @Before
    fun setup() {
        verifier = mockk(relaxed = true)
        clock = mockk(relaxed = true)
        strategy = mockk(relaxed = true)

        every { clock.nowMs() } returns 1000L
        every { strategy.strategyName } returns "TestStrategy"
        every { strategy.getRecoveryWindow(any()) } returns RecoveryWindow(chunkStartLba, chunkEndLba - chunkStartLba)

        engine = RereadEngine(
            verifier = verifier,
            maxRereads = maxRereads,
            clock = clock
        )
    }

    private fun createChunk(
        startLba: Int = chunkStartLba,
        endLba: Int = chunkEndLba,
        pcm: ByteArray = ByteArray(10) { it.toByte() },
        overlapHead: ByteArray = ByteArray(5) { it.toByte() },
        overlapTail: ByteArray = ByteArray(5) { (it + 5).toByte() },
        rereadCount: Int = 0
    ) = VerifiedChunk(
        startLba = startLba,
        endLba = endLba,
        pcm = pcm,
        overlapHead = overlapHead,
        overlapTail = overlapTail,
        rereadCount = rereadCount
    )

    @Test
    fun `executeAttempts returns Recovered on first attempt if stable against failed chunk and overlaps`() = runTest {
        val previousChunk = createChunk(endLba = chunkStartLba)
        val failedChunk = createChunk()
        val currentAttempt = createChunk() // Identical to failed chunk, so stable against attempt 0

        coEvery { strategy.performAttempt(any(), failedChunk, any()) } returns currentAttempt
        every { verifier.verifyOverlap(previousChunk.overlapTail, currentAttempt.overlapHead) } returns true
        every { verifier.overlapSizeBytes } returns 5 * 2352

        val overlaps = mutableListOf<ByteArray>()

        val result = engine.executeAttempts(
            context = defaultContext,
            strategy = strategy,
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk,
            candidateOverlaps = overlaps,
            readChunk = readChunkDummy
        )

        assertTrue(result is RereadExecutionResult.Recovered)
        val recovered = result as RereadExecutionResult.Recovered

        assertEquals(currentAttempt.copy(rereadCount = 1), recovered.chunk)
        assertEquals(1, recovered.metadata.rereadAttempts)
        assertTrue(recovered.metadata.recovered)

        assertEquals(1, overlaps.size)
        assertArrayEquals(currentAttempt.overlapHead, overlaps[0])
    }

    @Test
    fun `executeAttempts returns Recovered on second attempt if matches first attempt and overlaps`() = runTest {
        val previousChunk = createChunk(endLba = chunkStartLba)
        val failedChunk = createChunk(pcm = ByteArray(10) { 0 })
        val firstAttempt = createChunk(pcm = ByteArray(10) { 1 })
        val secondAttempt = createChunk(pcm = ByteArray(10) { 1 }) // matches first attempt

        coEvery { strategy.performAttempt(match { it.rereadAttempt == 1 }, failedChunk, any()) } returns firstAttempt
        coEvery { strategy.performAttempt(match { it.rereadAttempt == 2 }, failedChunk, any()) } returns secondAttempt

        // Return true only for the second attempt
        every { verifier.verifyOverlap(previousChunk.overlapTail, firstAttempt.overlapHead) } returns false
        every { verifier.verifyOverlap(previousChunk.overlapTail, secondAttempt.overlapHead) } returns true
        every { verifier.overlapSizeBytes } returns 5 * 2352

        val overlaps = mutableListOf<ByteArray>()

        val result = engine.executeAttempts(
            context = defaultContext,
            strategy = strategy,
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk,
            candidateOverlaps = overlaps,
            readChunk = readChunkDummy
        )

        assertTrue(result is RereadExecutionResult.Recovered)
        val recovered = result as RereadExecutionResult.Recovered

        assertEquals(secondAttempt.copy(rereadCount = 2), recovered.chunk)
        assertEquals(2, recovered.metadata.rereadAttempts)
        assertTrue(recovered.metadata.recovered)

        assertEquals(2, overlaps.size)
        assertArrayEquals(firstAttempt.overlapHead, overlaps[0])
        assertArrayEquals(secondAttempt.overlapHead, overlaps[1])
    }

    @Test
    fun `executeAttempts returns Failed when maxRereads exceeded without stability`() = runTest {
        val previousChunk = createChunk(endLba = chunkStartLba)
        val failedChunk = createChunk(pcm = ByteArray(10) { 0 })
        val attempt1 = createChunk(pcm = ByteArray(10) { 1 })
        val attempt2 = createChunk(pcm = ByteArray(10) { 2 })
        val attempt3 = createChunk(pcm = ByteArray(10) { 3 }) // Unstable, maxRereads is 3

        coEvery { strategy.performAttempt(match { it.rereadAttempt == 1 }, failedChunk, any()) } returns attempt1
        coEvery { strategy.performAttempt(match { it.rereadAttempt == 2 }, failedChunk, any()) } returns attempt2
        coEvery { strategy.performAttempt(match { it.rereadAttempt == 3 }, failedChunk, any()) } returns attempt3

        every { verifier.verifyOverlap(any(), any()) } returns true // Overlaps are fine, but unstable
        every { verifier.overlapSizeBytes } returns 5 * 2352

        val overlaps = mutableListOf<ByteArray>()

        val result = engine.executeAttempts(
            context = defaultContext,
            strategy = strategy,
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk,
            candidateOverlaps = overlaps,
            readChunk = readChunkDummy
        )

        assertTrue(result is RereadExecutionResult.Failed)
        val failed = result as RereadExecutionResult.Failed

        assertEquals(attempt3.copy(rereadCount = 3), failed.chunk)
        assertEquals(3, failed.metadata.rereadAttempts)
        assertFalse(failed.metadata.recovered)

        assertEquals(3, overlaps.size)
    }

    @Test
    fun `executeAttempts handles null returns from readChunk and continues`() = runTest {
        val previousChunk = createChunk(endLba = chunkStartLba)
        val failedChunk = createChunk(pcm = ByteArray(10) { 0 })
        val attempt2 = createChunk(pcm = ByteArray(10) { 1 })
        val attempt3 = createChunk(pcm = ByteArray(10) { 1 }) // Matches attempt 2 and overlaps

        coEvery { strategy.performAttempt(match { it.rereadAttempt == 1 }, failedChunk, any()) } returns null
        coEvery { strategy.performAttempt(match { it.rereadAttempt == 2 }, failedChunk, any()) } returns attempt2
        coEvery { strategy.performAttempt(match { it.rereadAttempt == 3 }, failedChunk, any()) } returns attempt3

        every { verifier.verifyOverlap(previousChunk.overlapTail, attempt2.overlapHead) } returns false
        every { verifier.verifyOverlap(previousChunk.overlapTail, attempt3.overlapHead) } returns true
        every { verifier.overlapSizeBytes } returns 5 * 2352

        val overlaps = mutableListOf<ByteArray>()

        val result = engine.executeAttempts(
            context = defaultContext,
            strategy = strategy,
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk,
            candidateOverlaps = overlaps,
            readChunk = readChunkDummy
        )

        assertTrue(result is RereadExecutionResult.Recovered)
        val recovered = result as RereadExecutionResult.Recovered

        assertEquals(attempt3.copy(rereadCount = 3), recovered.chunk)
        assertEquals(3, recovered.metadata.rereadAttempts)
        assertTrue(recovered.metadata.recovered)

        assertEquals(2, overlaps.size) // Only non-null attempts added
        assertArrayEquals(attempt2.overlapHead, overlaps[0])
        assertArrayEquals(attempt3.overlapHead, overlaps[1])
    }

    @Test
    fun `executeAttempts returns Failed when stable but overlap fails`() = runTest {
        val previousChunk = createChunk(endLba = chunkStartLba)
        val failedChunk = createChunk(pcm = ByteArray(10) { 0 })
        val attempt1 = createChunk(pcm = ByteArray(10) { 1 })
        val attempt2 = createChunk(pcm = ByteArray(10) { 1 }) // stable but overlap fails
        val attempt3 = createChunk(pcm = ByteArray(10) { 1 }) // stable but overlap fails

        coEvery { strategy.performAttempt(any(), failedChunk, any()) } returns attempt1

        every { verifier.verifyOverlap(any(), any()) } returns false // overlap check always fails
        every { verifier.overlapSizeBytes } returns 5 * 2352

        val overlaps = mutableListOf<ByteArray>()

        val result = engine.executeAttempts(
            context = defaultContext,
            strategy = strategy,
            previousVerifiedChunk = previousChunk,
            failedChunk = failedChunk,
            candidateOverlaps = overlaps,
            readChunk = readChunkDummy
        )

        assertTrue(result is RereadExecutionResult.Failed)
        val failed = result as RereadExecutionResult.Failed

        // It failed, so returns the last attempt
        assertEquals(attempt1.copy(rereadCount = 3), failed.chunk)
        assertEquals(3, failed.metadata.rereadAttempts)
        assertFalse(failed.metadata.recovered)

        assertEquals(3, overlaps.size)
    }
}
