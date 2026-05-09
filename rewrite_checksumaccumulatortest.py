content = """package com.bitperfect.app.usb

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

        val accumulator = ChecksumAccumulator(verifier, totalSamples)
        accumulator.accumulate(pcmData)

        val directResult = verifier.computeChecksumChunk(pcmData, samplePosition = 1, totalSamples = totalSamples)

        assertEquals(directResult.partialChecksum, accumulator.ripChecksum)
        assertEquals(1001L, accumulator.samplePosition)
    }
}
"""
with open("app/src/test/kotlin/com/bitperfect/app/usb/ChecksumAccumulatorTest.kt", "w") as f:
    f.write(content)
