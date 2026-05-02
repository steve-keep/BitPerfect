package com.bitperfect.core.utils

import com.bitperfect.core.models.DiscToc

data class AccurateRipDiscId(
    val id1: Long,   // TrackOffsetsAdded
    val id2: Long,   // TrackOffsetsMultiplied (may overflow Int, use Long)
    val id3: Long    // freedbId
) {
    fun toUrl(trackCount: Int): String {
        val id1Hex = String.format("%08x", id1 and 0xFFFFFFFFL)
        val id2Hex = String.format("%08x", id2 and 0xFFFFFFFFL)
        val id3Hex = String.format("%08x", id3)
        val dir = "${id1Hex[7]}/${id1Hex[6]}/${id1Hex[5]}"
        return "http://www.accuraterip.com/accuraterip/$dir/" +
            "dBAR-${trackCount.toString().padStart(3, '0')}-$id1Hex-$id2Hex-$id3Hex.bin"
    }
}

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

private const val LEAD_IN_FRAMES = 150

fun computeAccurateRipDiscId(toc: DiscToc): AccurateRipDiscId {
    var id1 = 0L
    var id2 = 0L
    for (entry in toc.tracks) {
        val lsn = (entry.lba - LEAD_IN_FRAMES).toLong()
        id1 += lsn
        id2 += maxOf(lsn, 1L) * entry.trackNumber
    }
    val lsnLeadOut = (toc.leadOutLba - LEAD_IN_FRAMES).toLong()
    id1 += lsnLeadOut
    id2 += lsnLeadOut * (toc.trackCount + 1)
    val id3 = computeFreedbId(toc)
    return AccurateRipDiscId(
        id1 and 0xFFFFFFFFL,
        id2 and 0xFFFFFFFFL,
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
