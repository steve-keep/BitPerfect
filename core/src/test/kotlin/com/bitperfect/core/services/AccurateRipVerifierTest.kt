package com.bitperfect.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class AccurateRipVerifierTest {

    private val verifier = AccurateRipVerifier()

    @Test
    fun `computeChecksumChunk - single chunk, all excluded, returns 0L`() {
        val samples = 588
        val pcmData = ByteArray(samples * 4) // 1 sector, 2352 bytes
        // Initialize with some data, should not matter since it's excluded
        for (i in pcmData.indices) {
            pcmData[i] = 1
        }

        val result = verifier.computeChecksumChunk(pcmData, 1, samples.toLong())

        assertEquals(0L, result.partialChecksum)
        assertEquals(samples.toLong() + 1, result.nextSamplePosition)
    }

    @Test
    fun `computeChecksumChunk - exclusion boundary 15 sectors`() {
        val sectors = 15
        val samples = sectors * 588L // 8820 samples
        val pcmData = ByteArray((samples * 4).toInt())

        // Fill PCM with 0x00000001 (little endian)
        for (i in 0 until (samples * 4).toInt() step 4) {
            pcmData[i] = 1
            pcmData[i+1] = 0
            pcmData[i+2] = 0
            pcmData[i+3] = 0
        }

        val result = verifier.computeChecksumChunk(pcmData, 1, samples)

        // Expected partial sum: sum of i for i in 2941..5880
        var expectedSum = 0L
        for (i in 2941L..5880L) {
            expectedSum += i * 1L
        }

        assertEquals(expectedSum, result.partialChecksum)
        assertEquals(samples + 1, result.nextSamplePosition)
    }

    @Test
    fun `computeChecksumChunk - multi-chunk consistency`() {
        val sectors = 20
        val totalSamples = sectors * 588L // 11760 samples
        val pcmData = ByteArray((totalSamples * 4).toInt())

        // Fill with some data
        for (i in pcmData.indices) {
            pcmData[i] = (i % 256).toByte()
        }

        // Single chunk calculation
        val singleResult = verifier.computeChecksumChunk(pcmData, 1, totalSamples)

        // Split into 3 chunks
        val chunk1SizeSamples = 3000
        val chunk2SizeSamples = 4000
        val chunk3SizeSamples = totalSamples.toInt() - chunk1SizeSamples - chunk2SizeSamples

        val chunk1 = pcmData.copyOfRange(0, chunk1SizeSamples * 4)
        val chunk2 = pcmData.copyOfRange(chunk1SizeSamples * 4, (chunk1SizeSamples + chunk2SizeSamples) * 4)
        val chunk3 = pcmData.copyOfRange((chunk1SizeSamples + chunk2SizeSamples) * 4, pcmData.size)

        val result1 = verifier.computeChecksumChunk(chunk1, 1, totalSamples)
        val result2 = verifier.computeChecksumChunk(chunk2, result1.nextSamplePosition, totalSamples)
        val result3 = verifier.computeChecksumChunk(chunk3, result2.nextSamplePosition, totalSamples)

        val multiChunkTotal = result1.partialChecksum + result2.partialChecksum + result3.partialChecksum

        assertEquals(singleResult.partialChecksum, multiChunkTotal)
        assertEquals(singleResult.nextSamplePosition, result3.nextSamplePosition)
        assertEquals(totalSamples + 1, result3.nextSamplePosition)
    }

    @Test
    fun `finaliseChecksum - masks correctly`() {
        val input = 0x1_FFFF_FFFFL
        val expected = 0xFFFFFFFFL

        assertEquals(expected, verifier.finaliseChecksum(input))

        val input2 = -1L // All bits set
        val expected2 = 0xFFFFFFFFL
        assertEquals(expected2, verifier.finaliseChecksum(input2))
    }
}
