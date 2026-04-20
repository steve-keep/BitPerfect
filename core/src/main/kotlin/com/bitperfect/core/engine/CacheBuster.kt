package com.bitperfect.core.engine

class CacheBuster(
    private val sectorReader: CdSectorReader,
    private val totalSectors: Long
) {
    fun flushCache(currentLba: Long) {
        // "Before each of the 16 reads in a batch, a decoy sector at targetLba + 10_000 (clamped to disc end) is read and discarded"
        val flushLba = (currentLba + 10_000).coerceAtMost(totalSectors - 1)
        if (flushLba >= 0) {
            sectorReader.readSectors(flushLba, 1, false, false)
        }
    }
}
