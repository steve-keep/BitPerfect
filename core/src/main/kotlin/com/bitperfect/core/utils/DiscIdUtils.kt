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
    var offsetsMultiplied = 0L
    for (entry in toc.tracks) {
        offsetsAdded += entry.lba
        offsetsMultiplied += maxOf(entry.lba.toLong(), 1L) * entry.trackNumber
    }
    offsetsAdded += toc.leadOutLba
    offsetsMultiplied += toc.leadOutLba.toLong() * (toc.trackCount + 1)
    val id3 = computeFreedbId(toc)
    return AccurateRipDiscId(
        offsetsAdded and 0xFFFFFFFFL,
        offsetsMultiplied and 0xFFFFFFFFL,
        id3
    )
}

fun computeMusicBrainzDiscId(toc: DiscToc): String {
    val tokens = mutableListOf<String>()

    // Token 1: first track number (always 1 as per instructions: "00000001")
    tokens.add(String.format("%02X", 1))

    // Token 2: last track number
    val lastTrack = toc.tracks.size
    tokens.add(String.format("%02X", lastTrack))

    // Token 3: lead-out LBA
    tokens.add(String.format("%08X", toc.leadOutLba))

    // Tokens 4-102: slots 1-99
    val trackMap = toc.tracks.mapIndexed { index, entry -> (index + 1) to entry }.toMap()
    for (i in 1..99) {
        val entry = trackMap[i]
        if (entry != null) {
            tokens.add(String.format("%08X", entry.lba))
        } else {
            tokens.add("00000000")
        }
    }

    val asciiString = tokens.joinToString("")
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val digest = md.digest(asciiString.toByteArray(Charsets.US_ASCII))

    // MusicBrainz base64: standard alphabet but + → . and / → _
    return android.util.Base64.encodeToString(digest, android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP)
        .replace('+', '.')
        .replace('/', '_')
        .replace('=', '-')
}
