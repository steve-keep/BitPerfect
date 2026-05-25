package com.bitperfect.app.ripping.paranoia

data class VerifiedChunk(
    val startLba: Int,
    val endLba: Int,
    val pcm: ByteArray,
    val overlapHead: ByteArray,
    val overlapTail: ByteArray,
    val rereadCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VerifiedChunk

        if (startLba != other.startLba) return false
        if (endLba != other.endLba) return false
        if (!pcm.contentEquals(other.pcm)) return false
        if (!overlapHead.contentEquals(other.overlapHead)) return false
        if (!overlapTail.contentEquals(other.overlapTail)) return false
        if (rereadCount != other.rereadCount) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startLba
        result = 31 * result + endLba
        result = 31 * result + pcm.contentHashCode()
        result = 31 * result + overlapHead.contentHashCode()
        result = 31 * result + overlapTail.contentHashCode()
        result = 31 * result + rereadCount
        return result
    }
}
