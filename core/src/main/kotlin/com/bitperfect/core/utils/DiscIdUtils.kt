package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc

data class AccurateRipDiscId(
    val id1: Long,   // TrackOffsetsAdded
    val id2: Long,   // TrackOffsetsMultiplied (may overflow Int, use Long)
    val id3: Long    // freedbId
)

fun computeFreedbId(toc: DiscToc): Long {
    // Sum of digit-sum of each track's start time in seconds, mod 255, shifted left 24 bits,
    // combined with total disc length in seconds and track count.
    // Standard freedb algorithm:
    fun digitSum(n: Int): Int = if (n == 0) 0 else (n % 10) + digitSum(n / 10)
    var checksum = 0
    for (entry in toc.tracks) {
        val seconds = entry.lba / 75
        checksum += digitSum(seconds)
    }
    checksum = checksum % 255
    val totalSeconds = toc.leadOutLba / 75
    val firstOffset = toc.tracks.firstOrNull()?.lba?.div(75) ?: 0
    val discLength = totalSeconds - firstOffset
    return ((checksum.toLong() shl 24) or (discLength.toLong() shl 8) or toc.trackCount.toLong())
}

fun computeAccurateRipDiscId(toc: DiscToc): AccurateRipDiscId {
    var offsetsAdded = 0L
    var offsetsMultiplied = 1L
    for (entry in toc.tracks) {
        offsetsAdded += entry.lba
        offsetsMultiplied *= maxOf(entry.lba.toLong(), 1L)
    }
    offsetsAdded += toc.leadOutLba
    offsetsMultiplied *= toc.leadOutLba.toLong()
    val id3 = computeFreedbId(toc)
    return AccurateRipDiscId(offsetsAdded, offsetsMultiplied, id3)
}
