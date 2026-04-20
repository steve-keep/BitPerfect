package com.bitperfect.core.engine

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureSectorExtractorTest {

    private val reader = mockk<CdSectorReader>()

    @Test
    fun testExtractSector_SuccessOnFirstPass() {
        val lba = 100L
        val data1 = ByteArray(2352) { 1 }
        val caps = DriveCapabilities(supportsC2 = false, hasCache = false)
        val extractor = SecureSectorExtractor(reader, caps)

        every { reader.readSectors(lba, 1, false, false) } returns data1 andThen data1

        val result = extractor.extractSector(lba)

        assertEquals(SectorResult.SUCCESS, result?.result)
        assertArrayEquals(data1, result?.data)
        verify(exactly = 2) { reader.readSectors(lba, 1, false, false) }
    }

    @Test
    fun testExtractSector_SuccessWithC2() {
        val lba = 100L
        // Valid C2 implies zero errors in C2 byte area
        val data1 = ByteArray(2352 + 294) { if (it < 2352) 1 else 0 }
        val caps = DriveCapabilities(supportsC2 = true, hasCache = false)
        val extractor = SecureSectorExtractor(reader, caps)

        every { reader.readSectors(lba, 1, true, false) } returns data1

        val result = extractor.extractSector(lba)

        assertEquals(SectorResult.SUCCESS, result?.result)
        // Check stripped C2 data is returned
        val expectedData = ByteArray(2352) { 1 }
        assertArrayEquals(expectedData, result?.data)
        verify(exactly = 1) { reader.readSectors(lba, 1, true, false) }
    }

    @Test
    fun testExtractSector_MismatchThenMajority() {
        val lba = 100L
        val data1 = ByteArray(2352) { 1 }
        val data2 = ByteArray(2352) { 2 }
        val dataMajority = ByteArray(2352) { 3 }
        val caps = DriveCapabilities(supportsC2 = false, hasCache = false)
        val extractor = SecureSectorExtractor(reader, caps)

        // Pass 1 & 2
        var readCount = 0
        every { reader.readSectors(lba, 1, false, false) } answers {
            readCount++
            when (readCount) {
                1 -> data1
                2 -> data2
                else -> dataMajority // Re-reads return majority
            }
        }

        val result = extractor.extractSector(lba)

        assertEquals(SectorResult.SUCCESS, result?.result)
        assertArrayEquals(dataMajority, result?.data)
        verify(exactly = 2 + 16) { reader.readSectors(lba, 1, false, false) }
    }

    @Test
    fun testExtractSector_NoMajoritySuspicious() {
        val lba = 100L
        val caps = DriveCapabilities(supportsC2 = false, hasCache = false)
        val extractor = SecureSectorExtractor(reader, caps, maxBatches = 1)

        // Pass 1 & 2, then 16 different reads -> no majority of 8
        var readCount = 0
        every { reader.readSectors(lba, 1, false, false) } answers {
            readCount++
            val data = ByteArray(2352) { (readCount % 7).toByte() } // Groups of size ~2-3, no group of 8
            data
        }

        val result = extractor.extractSector(lba)

        assertEquals(SectorResult.SUSPICIOUS, result?.result)
        // Total reads = 2 + 16 = 18.
        verify(exactly = 18) { reader.readSectors(lba, 1, false, false) }
    }

    @Test
    fun testExtractSector_CacheBusting() {
        val lba = 100L
        val caps = DriveCapabilities(supportsC2 = false, hasCache = true, estimatedCacheSizeSectors = 100)
        val extractor = SecureSectorExtractor(reader, caps, maxBatches = 1)

        var flushCount = 0
        val onFlushCache: () -> Unit = { flushCount++ }

        // Pass 1 & 2 read different data -> starts re-reads
        var readCount = 0
        every { reader.readSectors(lba, 1, false, false) } answers {
            readCount++
            ByteArray(2352) { readCount.toByte() }
        }

        extractor.extractSector(lba, onFlushCache = onFlushCache)

        // 1 flush before Pass 2
        // 16 flushes for the 16 re-reads
        assertEquals(17, flushCount)
    }

    @Test
    fun testExtractSector_NoCacheBusting() {
        val lba = 100L
        val caps = DriveCapabilities(supportsC2 = false, hasCache = false, estimatedCacheSizeSectors = 0)
        val extractor = SecureSectorExtractor(reader, caps, maxBatches = 1)

        var flushCount = 0
        val onFlushCache: () -> Unit = { flushCount++ }

        var readCount = 0
        every { reader.readSectors(lba, 1, false, false) } answers {
            readCount++
            ByteArray(2352) { readCount.toByte() }
        }

        extractor.extractSector(lba, onFlushCache = onFlushCache)

        assertEquals(0, flushCount)
    }
}
