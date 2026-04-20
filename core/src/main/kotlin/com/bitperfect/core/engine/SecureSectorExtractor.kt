package com.bitperfect.core.engine

import android.util.Log

class SecureSectorExtractor(
    private val sectorReader: CdSectorReader,
    private val capabilities: DriveCapabilities,
    private val maxBatches: Int = 3
) {
    private val TAG = "SecureSectorExtractor"

    fun extractSector(
        lba: Long,
        onReRead: ((Int) -> Unit)? = null,
        onFlushCache: (() -> Unit)? = null
    ): ExtractedSector? {
        // Pass 1
        val data1 = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: return null

        // If C2 is supported, we can potentially trust it if no errors reported
        if (capabilities.supportsC2 && !hasC2Errors(data1)) {
            return ExtractedSector(SectorResult.SUCCESS, stripC2(data1))
        }

        val requiresCacheBust = capabilities.estimatedCacheSizeSectors > 0 || capabilities.hasCache
        if (requiresCacheBust) {
            onFlushCache?.invoke()
        }

        // Pass 2
        val data2 = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: return null

        if (data1.contentEquals(data2)) {
            return ExtractedSector(SectorResult.SUCCESS, if (capabilities.supportsC2) stripC2(data1) else data1)
        }

        Log.w(TAG, "Mismatch at LBA $lba, starting re-reads (max batches: $maxBatches)")

        val allReads = mutableListOf<ByteArray>()
        allReads.add(data1)
        allReads.add(data2)

        var totalAttempts = 2

        for (batch in 0 until maxBatches) {
            for (i in 0 until 16) {
                totalAttempts++
                onReRead?.invoke(totalAttempts)

                if (requiresCacheBust) {
                    onFlushCache?.invoke()
                }

                val newData = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: continue
                allReads.add(newData)
            }

            // Find majority in current batch + previous reads? Or just across all reads so far?
            // "After each batch, findMajority() is computed; if >= 8 of 16 reads agree..."
            // Let's compute majority across ALL reads so far.
            val majorityData = findMajority(allReads)
            if (majorityData != null) {
                return ExtractedSector(SectorResult.SUCCESS, if (capabilities.supportsC2) stripC2(majorityData) else majorityData)
            }
        }

        // If no majority found after all batches, return the most common result across all reads as SUSPICIOUS
        Log.w(TAG, "No majority found for LBA $lba after $totalAttempts attempts. Returning most common as SUSPICIOUS.")
        val mostCommonData = findMostCommon(allReads)

        return ExtractedSector(SectorResult.SUSPICIOUS, if (capabilities.supportsC2) stripC2(mostCommonData) else mostCommonData)
    }

    private fun findMajority(reads: List<ByteArray>): ByteArray? {
        val groups = groupReads(reads)
        for (group in groups) {
            if (group.size >= 8) { // The spec says "if >= 8 of 16 reads agree" - we'll just look for 8 identical reads across all.
                return group[0]
            }
        }
        return null
    }

    private fun findMostCommon(reads: List<ByteArray>): ByteArray {
        val groups = groupReads(reads)
        var maxGroup = groups[0]
        for (group in groups) {
            if (group.size > maxGroup.size) {
                maxGroup = group
            }
        }
        return maxGroup[0]
    }

    private fun groupReads(reads: List<ByteArray>): List<List<ByteArray>> {
        val groups = mutableListOf<MutableList<ByteArray>>()
        for (read in reads) {
            var found = false
            for (group in groups) {
                if (group[0].contentEquals(read)) {
                    group.add(read)
                    found = true
                    break
                }
            }
            if (!found) {
                groups.add(mutableListOf(read))
            }
        }
        return groups
    }

    private fun hasC2Errors(data: ByteArray): Boolean {
        // C2 pointers are 294 bytes after 2352 bytes of audio data
        // 1 bit per byte. We just check if any of the 294 bytes are non-zero.
        for (i in 2352 until data.size) {
            if (data[i] != 0.toByte()) return true
        }
        return false
    }

    private fun stripC2(data: ByteArray): ByteArray {
        return data.copyOfRange(0, 2352)
    }
}
