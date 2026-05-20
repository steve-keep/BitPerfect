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

enum class SeamState {
    VERIFIED,
    RECOVERED,
    LOW_CONFIDENCE,
    DAMAGED
}

fun ByteArray.overlapHead(bytes: Int): ByteArray {
    val sizeToTake = minOf(bytes, this.size)
    if (sizeToTake <= 0) return ByteArray(0)
    return this.copyOfRange(0, sizeToTake)
}

fun ByteArray.overlapTail(bytes: Int): ByteArray {
    val sizeToTake = minOf(bytes, this.size)
    if (sizeToTake <= 0) return ByteArray(0)
    return this.copyOfRange(this.size - sizeToTake, this.size)
}
