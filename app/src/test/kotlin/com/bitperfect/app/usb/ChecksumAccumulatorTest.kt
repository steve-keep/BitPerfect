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
        // Weighted 1: 0x01020304 * 1 = 0x01020304 -> bytes: 04 03 02 01
        // Sample 2 value: 0x05060708, index 2
        // Weighted 2: 0x05060708 * 2 = 0x0A0C0E10 -> bytes: 10 0E 0C 0A

        val crc = java.util.zip.CRC32()
        crc.update(byteArrayOf(0x04, 0x03, 0x02, 0x01))
        crc.update(byteArrayOf(0x10, 0x0E, 0x0C, 0x0A))
        val expectedV2 = crc.value and 0xFFFFFFFFL

        val checksums = accumulator.finalise()
        assertEquals(expectedV2, checksums.second)
    }

    @Test
    fun `V2 includes first 2940 samples on first track`() {
        val totalSamples = 10000L

        // 2940 samples exactly
        val skippedSamplesCount = 2940
        val pcmData = createDummyPcmData(skippedSamplesCount * 4)

        val accumulator = ChecksumAccumulator(totalSamples, isFirstTrack = true, isLastTrack = false)
        accumulator.accumulate(pcmData)

        val checksums = accumulator.finalise()

        // V1 should still be 0 since it skips the first 2940 samples
        assertEquals(0L, checksums.first)

        // Manual V2 calculation for all 2940 samples
        val crc = java.util.zip.CRC32()
        val bytes = ByteArray(4)
        for (i in 0 until skippedSamplesCount) {
            val sample = ((pcmData[i*4].toLong() and 0xFF) or
                         ((pcmData[i*4+1].toLong() and 0xFF) shl 8) or
                         ((pcmData[i*4+2].toLong() and 0xFF) shl 16) or
                         ((pcmData[i*4+3].toLong() and 0xFF) shl 24))
            val sampleValue = sample and 0xFFFFFFFFL
            val currentSamplePos = (i + 1).toLong()

            val weighted = (sampleValue * currentSamplePos) and 0xFFFFFFFFL
            bytes[0] = (weighted and 0xFF).toByte()
            bytes[1] = ((weighted shr 8) and 0xFF).toByte()
            bytes[2] = ((weighted shr 16) and 0xFF).toByte()
            bytes[3] = ((weighted shr 24) and 0xFF).toByte()
            crc.update(bytes)
        }
        val expectedV2 = crc.value and 0xFFFFFFFFL

        assertEquals(expectedV2, checksums.second)
    }

    @Test
    fun `V2 includes last 2940 samples on last track`() {
        val totalSamples = 3000L // Small total samples, only first 60 samples would be accumulated for V1
        val pcmData = ByteArray(totalSamples.toInt() * 4)

        // Populate all samples with value 1
        for (i in pcmData.indices step 4) {
            pcmData[i] = 1
        }

        val accumulator = ChecksumAccumulator(totalSamples, isFirstTrack = false, isLastTrack = true)
        accumulator.accumulate(pcmData)

        val checksums = accumulator.finalise()

        // V1 should only include the first 60 samples (3000 - 2940 = 60)
        var expectedV1 = 0L
        for (i in 1..60) {
            expectedV1 = (expectedV1 + 1L * i) and 0xFFFFFFFFL
        }
        assertEquals(expectedV1, checksums.first)

        // Manual V2 calculation for all 3000 samples
        val crc = java.util.zip.CRC32()
        val bytes = ByteArray(4)
        for (i in 1..3000) {
            val weighted = (1L * i) and 0xFFFFFFFFL
            bytes[0] = (weighted and 0xFF).toByte()
            bytes[1] = ((weighted shr 8) and 0xFF).toByte()
            bytes[2] = ((weighted shr 16) and 0xFF).toByte()
            bytes[3] = ((weighted shr 24) and 0xFF).toByte()
            crc.update(bytes)
        }
        val expectedV2 = crc.value and 0xFFFFFFFFL

        assertEquals(expectedV2, checksums.second)
    }
}
