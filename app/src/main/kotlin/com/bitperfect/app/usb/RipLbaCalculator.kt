package com.bitperfect.app.usb

/**
 * Calculates the range of physical LBAs that the read loop should request for a single track.
 *
 * @param trackLba      The track's LBA from the TOC (pregap-normalised, e.g. 150-based)
 * @param nextLba       The LBA of the next track's start (or leadOutLba for the last track)
 * @param tocOffset     Number of whole sectors by which the drive read offset shifts the window
 * @param pregapOffset  Pregap normalisation offset stored in DiscToc (typically 150 or 0)
 * @param isLastTrack   Whether this is the final track on the disc
 * @return              Pair of (firstLba, lastLba) — the inclusive range of LBAs to request.
 *                      lastLba must be strictly less than leadOutLba for the last track.
 */
internal fun ripLbaRange(
    trackLba: Int,
    nextLba: Int,
    tocOffset: Int,
    pregapOffset: Int,
    isLastTrack: Boolean
): Pair<Int, Int> {
    val lbaStart = trackLba + tocOffset
    val totalSectors = nextLba - trackLba
    val effectiveTotalSectors = if (isLastTrack) totalSectors - tocOffset else totalSectors
    val firstLba = lbaStart - pregapOffset
    val lastLba  = firstLba + effectiveTotalSectors - 1
    return Pair(firstLba, lastLba)
}