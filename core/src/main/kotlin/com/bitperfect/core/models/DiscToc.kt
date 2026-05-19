package com.bitperfect.core.models

data class TocEntry(val trackNumber: Int, val lba: Int)

data class DiscToc(
    val tracks: List<TocEntry>,   // audio tracks only, index 01 positions
    val leadOutLba: Int,
    val pregapOffset: Int = 0,    // Offset added to normalize 0-based LBAs to 150-based
    val audioLeadOutLba: Int? = null   // non-null only for CD-Extra discs
) {
    val trackCount: Int get() = tracks.size
}
