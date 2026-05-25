package com.bitperfect.app.ripping.paranoia

class OverlapVerifier(
    private val overlapSizeSectors: Int = 6
) {
    val overlapSizeBytes = overlapSizeSectors * 2352

    fun extractOverlapHead(pcm: ByteArray): ByteArray {
        return if (pcm.size >= overlapSizeBytes) {
            pcm.copyOfRange(0, overlapSizeBytes)
        } else {
            pcm.copyOf()
        }
    }

    fun extractOverlapTail(pcm: ByteArray): ByteArray {
        return if (pcm.size >= overlapSizeBytes) {
            pcm.copyOfRange(pcm.size - overlapSizeBytes, pcm.size)
        } else {
            pcm.copyOf()
        }
    }

    fun verifyOverlap(tail: ByteArray, head: ByteArray): Boolean {
        if (tail.size != head.size) return false
        return tail.contentEquals(head)
    }

    fun commitVerifiedAudio(chunk: VerifiedChunk, isFinal: Boolean = false): ByteArray {
        return if (isFinal) {
            // Final chunk commits everything
            chunk.pcm
        } else {
            // Trim the synthetic overlap (tail)
            if (chunk.pcm.size > overlapSizeBytes) {
                chunk.pcm.copyOfRange(0, chunk.pcm.size - overlapSizeBytes)
            } else {
                ByteArray(0) // Should not happen if chunk is at least overlapSize
            }
        }
    }
}
