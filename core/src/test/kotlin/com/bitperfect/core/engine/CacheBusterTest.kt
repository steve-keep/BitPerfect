package com.bitperfect.core.engine

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class CacheBusterTest {

    @Test
    fun testFlushCache() {
        val reader = mockk<CdSectorReader>()
        every { reader.readSectors(any(), any(), any(), any()) } returns ByteArray(2352)

        val totalSectors = 200_000L
        val cacheBuster = CacheBuster(reader, totalSectors)

        val lba = 100L
        cacheBuster.flushCache(lba)

        verify(exactly = 1) { reader.readSectors(lba + 10_000, 1, false, false) }
    }

    @Test
    fun testFlushCache_ClampedToEnd() {
        val reader = mockk<CdSectorReader>()
        every { reader.readSectors(any(), any(), any(), any()) } returns ByteArray(2352)

        val totalSectors = 5_000L
        val cacheBuster = CacheBuster(reader, totalSectors)

        val lba = 4_500L
        cacheBuster.flushCache(lba)

        verify(exactly = 1) { reader.readSectors(totalSectors - 1, 1, false, false) }
    }
}
