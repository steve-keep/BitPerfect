package com.bitperfect.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AccurateRipVerifierTest {

    private val verifier = AccurateRipVerifier()

    @Test
    fun `parseAccurateRipResponse - empty input returns empty map`() {
        val result = verifier.parseAccurateRipResponse(ByteArray(0))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseAccurateRipResponse - single disc entry, two tracks`() {
        val buffer = ByteBuffer.allocate(9 + 2 * 9).order(ByteOrder.LITTLE_ENDIAN)
        // Header
        buffer.put(2.toByte()) // trackCount = 2
        buffer.putInt(0x11111111) // discId1
        buffer.putInt(0x22222222) // discId2

        // Track 1
        buffer.put(5.toByte()) // confidence
        buffer.putInt(0xAAAAAAAA.toInt()) // crcV1
        buffer.putInt(0xBBBBBBBB.toInt()) // crcV2

        // Track 2
        buffer.put(10.toByte()) // confidence
        buffer.putInt(0xCCCCCCCC.toInt()) // crcV1
        buffer.putInt(0xDDDDDDDD.toInt()) // crcV2

        val result = verifier.parseAccurateRipResponse(buffer.array())

        assertEquals(2, result.size)
        assertEquals(1, result[1]?.size)
        assertEquals(0xAAAAAAAAL, result[1]?.get(0)?.checksum)
        assertEquals(5, result[1]?.get(0)?.confidence)

        assertEquals(1, result[2]?.size)
        assertEquals(0xCCCCCCCCL, result[2]?.get(0)?.checksum)
        assertEquals(10, result[2]?.get(0)?.confidence)
    }

    @Test
    fun `parseAccurateRipResponse - multiple disc entries`() {
        val buffer = ByteBuffer.allocate((9 + 1 * 9) * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Entry 1
        buffer.put(1.toByte()) // trackCount = 1
        buffer.putInt(0x11111111) // discId1
        buffer.putInt(0x22222222) // discId2
        buffer.put(3.toByte()) // confidence
        buffer.putInt(0x11111111) // crcV1
        buffer.putInt(0) // crcV2

        // Entry 2
        buffer.put(1.toByte()) // trackCount = 1
        buffer.putInt(0x33333333) // discId1
        buffer.putInt(0x44444444) // discId2
        buffer.put(7.toByte()) // confidence
        buffer.putInt(0x33333333) // crcV1
        buffer.putInt(0) // crcV2

        val result = verifier.parseAccurateRipResponse(buffer.array())

        assertEquals(1, result.size) // Only 1 track in total
        assertEquals(2, result[1]?.size) // Track 1 should have 2 entries

        assertEquals(0x11111111L, result[1]?.get(0)?.checksum)
        assertEquals(3, result[1]?.get(0)?.confidence)

        assertEquals(0x33333333L, result[1]?.get(1)?.checksum)
        assertEquals(7, result[1]?.get(1)?.confidence)
    }

    @Test
    fun `parseAccurateRipResponse - truncated entry`() {
        // Header promises 3 tracks, but we only supply 2 tracks
        val buffer = ByteBuffer.allocate(9 + 2 * 9).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(3.toByte()) // trackCount = 3
        buffer.putInt(0x11111111) // discId1
        buffer.putInt(0x22222222) // discId2

        // Track 1
        buffer.put(5.toByte())
        buffer.putInt(0xAAAAAAAA.toInt())
        buffer.putInt(0)

        // Track 2
        buffer.put(10.toByte())
        buffer.putInt(0xCCCCCCCC.toInt())
        buffer.putInt(0)

        val result = verifier.parseAccurateRipResponse(buffer.array())

        // Should break and return empty map
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseAccurateRipResponse - discId1 and discId2 read without corrupting buffer offsets`() {
        val buffer = ByteBuffer.allocate(9 + 1 * 9).order(ByteOrder.LITTLE_ENDIAN)
        // Header
        buffer.put(1.toByte()) // trackCount = 1
        buffer.putInt(0x12345678) // discId1
        buffer.putInt(0x87654321.toInt()) // discId2

        // Track 1
        buffer.put(5.toByte()) // confidence
        buffer.putInt(0xABCDEF01.toInt()) // crcV1
        buffer.putInt(0) // crcV2

        val result = verifier.parseAccurateRipResponse(buffer.array())

        // If discId1 and discId2 were not read correctly, the buffer offset would be wrong
        // and we wouldn't get the correct track checksum.
        assertEquals(1, result.size)
        assertEquals(0xABCDEF01L, result[1]?.get(0)?.checksum)
        assertEquals(5, result[1]?.get(0)?.confidence)
    }

    @Test
    fun `computeChecksumChunk - single chunk, all excluded, returns 0L`() {
        val samples = 588
        val pcmData = ByteArray(samples * 4) // 1 sector, 2352 bytes
        // Initialize with some data, should not matter since it's excluded
        for (i in pcmData.indices) {
            pcmData[i] = 1
        }

        val result = verifier.computeChecksumChunk(
            pcmData, 1, samples.toLong(),
            isFirstTrack = true, isLastTrack = true
        )

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

        val result = verifier.computeChecksumChunk(
            pcmData, 1, samples,
            isFirstTrack = true, isLastTrack = true
        )

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
        val singleResult = verifier.computeChecksumChunk(
            pcmData, 1, totalSamples,
            isFirstTrack = true, isLastTrack = true
        )

        // Split into 3 chunks
        val chunk1SizeSamples = 3000
        val chunk2SizeSamples = 4000
        val chunk3SizeSamples = totalSamples.toInt() - chunk1SizeSamples - chunk2SizeSamples

        val chunk1 = pcmData.copyOfRange(0, chunk1SizeSamples * 4)
        val chunk2 = pcmData.copyOfRange(chunk1SizeSamples * 4, (chunk1SizeSamples + chunk2SizeSamples) * 4)
        val chunk3 = pcmData.copyOfRange((chunk1SizeSamples + chunk2SizeSamples) * 4, pcmData.size)

        val result1 = verifier.computeChecksumChunk(chunk1, 1, totalSamples, isFirstTrack = true, isLastTrack = true)
        val result2 = verifier.computeChecksumChunk(chunk2, result1.nextSamplePosition, totalSamples, isFirstTrack = true, isLastTrack = true)
        val result3 = verifier.computeChecksumChunk(chunk3, result2.nextSamplePosition, totalSamples, isFirstTrack = true, isLastTrack = true)

        val multiChunkTotal = (result1.partialChecksum + result2.partialChecksum + result3.partialChecksum) and 0xFFFFFFFFL

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

    @Test
    fun `computeChecksumChunk - no overflow on full-length track`() {
        // Track 1 of White Blood Cells: 13901 sectors = 8,173,788 samples
        // Overflow would occur at ~157 sectors with a 64-bit Long accumulator.
        // Use 300 sectors (176,400 samples) — well past the overflow point.
        val sectors = 300
        val totalSamples = sectors * 588L
        // Fill PCM with 0xFFFFFFFF (max value) to maximise accumulator growth
        val pcmData = ByteArray(sectors * 2352) { 0xFF.toByte() }

        val result = verifier.computeChecksumChunk(
            pcmData, 1L, totalSamples,
            isFirstTrack = false, isLastTrack = false
        )

        // Key assertion: partialChecksum must be in 0..0xFFFFFFFF (32-bit range)
        // A Long overflow would produce a value outside this range or a negative value
        assertTrue(
            "partialChecksum overflowed 32 bits: ${result.partialChecksum}",
            result.partialChecksum in 0L..0xFFFFFFFFL
        )

        // Verify against Python reference for the same input:
        // sum((0xFFFFFFFF * i) & 0xFFFFFFFF for i in range(1, 176401)) & 0xFFFFFFFF
        // = 0x60a316f8  (pre-computed — include this assertion)
        assertEquals(0x60A316F8L, result.partialChecksum)
    }

    @Test
    fun `computeChecksumChunk - chunked and single-pass agree at overflow-triggering size`() {
        val sectors = 300
        val totalSamples = sectors * 588L
        val pcmData = ByteArray(sectors * 2352)
        // Fill with a non-trivial pattern
        for (i in pcmData.indices) pcmData[i] = (i * 31 + 7).toByte()

        // Single pass
        val single = verifier.computeChecksumChunk(pcmData, 1L, totalSamples)

        // Chunked (8 sectors per chunk, matching RipManager default)
        var accumulated = 0L
        var pos = 1L
        var offset = 0
        while (offset < pcmData.size) {
            val chunkBytes = minOf(8 * 2352, pcmData.size - offset)
            val chunk = pcmData.copyOfRange(offset, offset + chunkBytes)
            val r = verifier.computeChecksumChunk(chunk, pos, totalSamples)
            accumulated = (accumulated + r.partialChecksum) and 0xFFFFFFFFL
            pos = r.nextSamplePosition
            offset += chunkBytes
        }

        assertEquals(
            verifier.finaliseChecksum(single.partialChecksum),
            verifier.finaliseChecksum(accumulated)
        )
    }
}
