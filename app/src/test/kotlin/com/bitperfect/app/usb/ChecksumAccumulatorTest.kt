package com.bitperfect.app.usb

import com.bitperfect.core.services.AccurateRipVerifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecksumAccumulatorTest {

    private fun createDummyPcmData(sizeInBytes: Int): ByteArray {
        val data = ByteArray(sizeInBytes)
        for (i in data.indices) {
            data[i] = (i % 256).toByte()
        }
        return data
    }

    @Test
    fun testAccumulatesAndAdvancesPosition() {
        val verifier = AccurateRipVerifier()
        val totalSamples = 10000L
        val pcmData = createDummyPcmData(4000) // 1000 samples

        val accumulator = ChecksumAccumulator(totalSamples)
        accumulator.accumulate(pcmData)

        val directResult = verifier.computeChecksumChunk(pcmData, samplePosition = 1, totalSamples = totalSamples)

        assertEquals(directResult.partialChecksum, accumulator.ripChecksumV1)
        assertEquals(1001L, accumulator.samplePosition)
    }

    @Test
    fun `V2 accumulation correctness`() {
        val totalSamples = 10000L
        val pcmData = ByteArray(8) // 2 samples

        // Sample 1: 0x01020304 (little-endian bytes: 04 03 02 01)
        pcmData[0] = 0x04
        pcmData[1] = 0x03
        pcmData[2] = 0x02
        pcmData[3] = 0x01

        // Sample 2: 0x05060708 (little-endian bytes: 08 07 06 05)
        pcmData[4] = 0x08
        pcmData[5] = 0x07
        pcmData[6] = 0x06
        pcmData[7] = 0x05

        val accumulator = ChecksumAccumulator(totalSamples)
        accumulator.accumulate(pcmData)

        // Manual V2 calculation
        // Sample 1 value: 0x01020304, index 1
        // Weighted 1: 0x01020304 * 1 = 0x01020304
        val calc1 = 0x01020304L * 1L
        val lo1 = calc1 and 0xFFFFFFFFL
        val hi1 = (calc1 ushr 32) and 0xFFFFFFFFL

        // Sample 2 value: 0x05060708, index 2
        // Weighted 2: 0x05060708 * 2 = 0x0A0C0E10
        val calc2 = 0x05060708L * 2L
        val lo2 = calc2 and 0xFFFFFFFFL
        val hi2 = (calc2 ushr 32) and 0xFFFFFFFFL

        val expectedV2 = (lo1 + hi1 + lo2 + hi2) and 0xFFFFFFFFL

        val checksums = accumulator.finalise()
        assertEquals(expectedV2, checksums.second)
    }

    @Test
    fun `V2 skips first 2940 samples on first track`() {
        val totalSamples = 10000L

        // 2940 samples exactly
        val skippedSamplesCount = 2940
        val pcmData = createDummyPcmData(skippedSamplesCount * 4)

        val accumulator = ChecksumAccumulator(totalSamples, isFirstTrack = true, isLastTrack = false)
        accumulator.accumulate(pcmData)

        val checksums = accumulator.finalise()

        // Both V1 and V2 should be 0 since they skip the first 2940 samples
        assertEquals(0L, checksums.first)
        assertEquals(0L, checksums.second)
    }

    @Test
    fun `V2 skips last 2940 samples on last track`() {
        val totalSamples = 3000L // Small total samples, only first 60 samples would be accumulated for V1 and V2
        val pcmData = ByteArray(totalSamples.toInt() * 4)

        // Populate all samples with value 1
        for (i in pcmData.indices step 4) {
            pcmData[i] = 1
        }

        val accumulator = ChecksumAccumulator(totalSamples, isFirstTrack = false, isLastTrack = true)
        accumulator.accumulate(pcmData)

        val checksums = accumulator.finalise()

        // Both V1 and V2 should only include the first 60 samples (3000 - 2940 = 60)
        var expectedV1 = 0L
        var expectedV2 = 0L
        for (i in 1..60) {
            expectedV1 = (expectedV1 + 1L * i) and 0xFFFFFFFFL

            val calc = 1L * i
            val lo = calc and 0xFFFFFFFFL
            val hi = (calc ushr 32) and 0xFFFFFFFFL
            expectedV2 = (expectedV2 + lo + hi) and 0xFFFFFFFFL
        }

        assertEquals(expectedV1, checksums.first)
        assertEquals(expectedV2, checksums.second)
    }
}
