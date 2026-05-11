package com.bitperfect.app.ui.calibration

/**
 * Calculates the starting native LBA and total sector count for the calibration raw read.
 *
 * Starts at LBA 1 (not 0) because native LBA 0 is unreadable on most drives.
 * Reads [totalSectors + overshootSectors] sectors to provide headroom for the
 * ±[maxOffsetSamples] offset scan.
 *
 * @param trackLba          Pregap-normalised LBA of track 1 (e.g. 150)
 * @param pregapOffset      Pregap normalisation value stored in DiscToc (typically 150)
 * @param totalSectors      Number of sectors in the track (nextLba - trackLba)
 * @param overshootSectors  Extra sectors to read past the track end for positive offset headroom
 * @return Pair of (firstNativeLba, sectorsToRead)
 */
internal fun calibrationLbaRange(
    trackLba: Int,
    pregapOffset: Int,
    totalSectors: Int,
    overshootSectors: Int = 7
): Pair<Int, Int> {
    val firstNativeLba = trackLba - pregapOffset + 1   // skip unreadable LBA 0
    val sectorsToRead  = totalSectors + overshootSectors
    return Pair(firstNativeLba, sectorsToRead)
}
