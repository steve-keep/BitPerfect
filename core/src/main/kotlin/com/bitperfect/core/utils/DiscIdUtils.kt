package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc
import java.security.MessageDigest
import java.util.Base64

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

fun computeMusicBrainzDiscId(toc: DiscToc): String {
    val firstTrack = toc.tracks.firstOrNull()?.trackNumber ?: 1
    val lastTrack = toc.tracks.lastOrNull()?.trackNumber ?: 1

    val sb = StringBuilder()

    // First track, Last track, Lead-out LBA + 150
    sb.append(String.format("%02X", firstTrack)).append(" ")
    sb.append(String.format("%02X", lastTrack)).append(" ")
    sb.append(String.format("%08X", toc.leadOutLba + 150))

    // 99 tracks LBA + 150
    for (i in 1..99) {
        val entry = toc.tracks.find { it.trackNumber == i }
        sb.append(" ")
        if (entry != null) {
            sb.append(String.format("%08X", entry.lba + 150))
        } else {
            sb.append("00000000")
        }
    }

    val digest = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
    val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return base64
}
