package com.bitperfect.app.ripping.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineModelsTest {

    @Test
    fun testRawChunkEqualityBasedOnContent() {
        val chunk1 = RawChunk(startLba = 0, sectorCount = 1, pcmData = byteArrayOf(1, 2, 3))
        val chunk2 = RawChunk(startLba = 0, sectorCount = 1, pcmData = byteArrayOf(1, 2, 3))

        assertEquals(chunk1, chunk2)
        assertEquals(chunk1.hashCode(), chunk2.hashCode())
    }

    @Test
    fun testRawChunkEqualityFalseWhenPcmDataDiffers() {
        val chunk1 = RawChunk(startLba = 0, sectorCount = 1, pcmData = byteArrayOf(1, 2, 3))
        val chunk2 = RawChunk(startLba = 0, sectorCount = 1, pcmData = byteArrayOf(1, 2, 4))

        assertNotEquals(chunk1, chunk2)
        assertFalse(chunk1 == chunk2)
    }

    @Test
    fun testSuspendAndFlushIsSingleton() {
        val command1 = ReadCommand.SuspendAndFlush
        val command2 = ReadCommand.SuspendAndFlush

        assertSame(command1, command2)
    }

    @Test
    fun testStartSequentialEqualityIsStructural() {
        val command1 = ReadCommand.StartSequential(startLba = 0, endLba = 100, chunkSize = 27)
        val command2 = ReadCommand.StartSequential(startLba = 0, endLba = 100, chunkSize = 27)
        val command3 = ReadCommand.StartSequential(startLba = 0, endLba = 100, chunkSize = 28)

        assertEquals(command1, command2)
        assertNotEquals(command1, command3)
    }

    @Test
    fun testRipPipelineConfigDefaults() {
        val config = RipPipelineConfig()

        assertEquals(27, config.chunkSize)
        assertEquals(6, config.overlapSize)
        assertEquals(50, config.channelCapacity)
    }

    @Test
    fun testRipPipelineConfigThrowsWhenChunkSizeLessThanOrEqualToOverlapSize() {
        assertThrows(IllegalArgumentException::class.java) {
            RipPipelineConfig(chunkSize = 6, overlapSize = 6)
        }

        assertThrows(IllegalArgumentException::class.java) {
            RipPipelineConfig(chunkSize = 5, overlapSize = 6)
        }
    }

    @Test
    fun testRipPipelineConfigThrowsWhenChannelCapacityZeroOrNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            RipPipelineConfig(channelCapacity = 0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            RipPipelineConfig(channelCapacity = -1)
        }
    }
}