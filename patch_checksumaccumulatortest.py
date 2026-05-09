import re

with open("app/src/test/kotlin/com/bitperfect/app/usb/ChecksumAccumulatorTest.kt", "r") as f:
    content = f.read()

# Replace testZeroOffsetProducesSameResultAsDirectVerifier
old_test_zero = """    @Test
    fun testZeroOffsetProducesSameResultAsDirectVerifier() {
        val verifier = AccurateRipVerifier()
        val totalSamples = 10000L
        val pcmData = createDummyPcmData(4000) // 1000 samples

        val accumulator = ChecksumAccumulator(verifier, totalSamples, driveOffset = 0)
        accumulator.accumulate(pcmData)

        val directResult = verifier.computeChecksumChunk(pcmData, samplePosition = 1, totalSamples = totalSamples)

        assertEquals(directResult.partialChecksum, accumulator.ripChecksum)
        assertEquals(1001L, accumulator.samplePosition)
    }"""
new_test_zero = """    @Test
    fun testZeroOffsetProducesSameResultAsDirectVerifier() {
        val verifier = AccurateRipVerifier()
        val totalSamples = 10000L
        val pcmData = createDummyPcmData(4000) // 1000 samples

        val accumulator = ChecksumAccumulator(verifier, totalSamples)
        accumulator.accumulate(pcmData)

        val directResult = verifier.computeChecksumChunk(pcmData, samplePosition = 1, totalSamples = totalSamples)

        assertEquals(directResult.partialChecksum, accumulator.ripChecksum)
        assertEquals(1001L, accumulator.samplePosition)
    }"""
content = content.replace(old_test_zero, new_test_zero)

# The other tests are testing offset behavior inside the accumulator. But now the accumulator doesn't know about offset.
# We should rewrite these tests or delete them, since the offset is handled externally.
# I will just remove the tests that specifically test driveOffset logic inside accumulator.

# Let's write a python script to just rewrite the file from scratch because it's simpler.
