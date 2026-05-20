package com.bitperfect.app.usb

data class PendingChunk(
    val startLba: Int,
    val endLba: Int,
    val pcm: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PendingChunk

        if (startLba != other.startLba) return false
        if (endLba != other.endLba) return false
        if (!pcm.contentEquals(other.pcm)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startLba
        result = 31 * result + endLba
        result = 31 * result + pcm.contentHashCode()
        return result
    }
}

/**
 * Zero-allocation overlap comparison.
 * Compares the tail of [this] ByteArray with the head of [other] ByteArray.
 * @param requestedOverlapBytes The desired overlap size. The actual comparison
 * will be bounded by the available size in both arrays.
 * @return The effective overlap size used for the comparison, or 0 if they do not match.
 */
/**
 * Zero-allocation overlap comparison.
 * Compares the tail of [this] ByteArray with the head of [other] ByteArray.
 * @param effectiveOverlap The exact number of bytes to compare.
 * @return True if they match, false otherwise.
 */
fun ByteArray.matchOverlapTailWithHead(other: ByteArray, effectiveOverlap: Int): Boolean {
    if (effectiveOverlap <= 0 || this.size < effectiveOverlap || other.size < effectiveOverlap) return false

    val thisOffset = this.size - effectiveOverlap
    val otherOffset = 0

    for (i in 0 until effectiveOverlap) {
        if (this[thisOffset + i] != other[otherOffset + i]) {
            return false // Mismatch
        }
    }
    return true // Match
}
