package com.bitperfect.app.ripping.paranoia

import com.bitperfect.core.utils.AppLogger

class OverlapVerifier(
    private val overlapSizeSectors: Int = 6,
    private val maxRereads: Int = 6,
    private val trackNumber: Int
) {
    private val overlapSizeBytes = overlapSizeSectors * 2352

    // We hold one pending chunk
    private var pendingChunk: VerifiedChunk? = null

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

    /**
     * Process a newly read chunk of PCM data.
     *
     * @param pcm The raw audio data read from the drive.
     * @param startLba The starting LBA of this chunk.
     * @param endLba The ending LBA of this chunk (exclusive).
     * @param isFinal True if this is the last chunk to be read.
     * @param readExecutor A function that allows reading sectors from the drive in case of a reread.
     *
     * @return A ByteArray of verified PCM data ready to be committed, or null if nothing is ready yet.
     */
    fun processChunk(
        pcm: ByteArray,
        startLba: Int,
        endLba: Int,
        isFinal: Boolean,
        readExecutor: (lba: Int, sectors: Int) -> ByteArray?
    ): ByteArray? {
        val head = extractOverlapHead(pcm)
        val tail = extractOverlapTail(pcm)

        var currentChunk = VerifiedChunk(
            startLba = startLba,
            endLba = endLba,
            pcm = pcm,
            overlapHead = head,
            overlapTail = tail,
            confidence = RipConfidence.HIGH,
            rereadCount = 0
        )

        var committedPcm: ByteArray? = null

        if (pendingChunk != null) {
            val pChunk = pendingChunk!!
            val match = verifyOverlap(pChunk.overlapTail, currentChunk.overlapHead)

            if (match) {
                AppLogger.d("OverlapVerifier", "overlap_match track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - overlapSizeSectors} confidence=HIGH")
                committedPcm = commitVerifiedAudio(pChunk, isFinal = false)
            } else {
                AppLogger.w("OverlapVerifier", "overlap_mismatch track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - overlapSizeSectors}")
                // Mismatch, need to recover current chunk
                val recoveredChunk = performRereadRecovery(currentChunk, pChunk.overlapTail, readExecutor)
                currentChunk = recoveredChunk

                if (recoveredChunk.confidence == RipConfidence.HIGH) {
                    AppLogger.d("OverlapVerifier", "reread_recovered track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - overlapSizeSectors} confidence=HIGH")
                } else {
                    AppLogger.w("OverlapVerifier", "reread_failed track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - overlapSizeSectors} confidence=LOW")
                    AppLogger.w("OverlapVerifier", "suspicious_region track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - overlapSizeSectors}")
                }

                // Commit pending chunk regardless (it's the best we have)
                committedPcm = commitVerifiedAudio(pChunk, isFinal = false)
            }
        }

        // Advance chunk
        pendingChunk = currentChunk

        return committedPcm
    }

    /**
     * Finalizes the verifier. Call this after all chunks have been processed (i.e. `processChunk`
     * was called with `isFinal = true` for the last chunk).
     * This will commit the final pending chunk (including its tail).
     *
     * @return The remaining verified PCM data ready to be committed.
     */
    fun finalize(): ByteArray? {
        val pChunk = pendingChunk ?: return null
        pendingChunk = null
        return commitVerifiedAudio(pChunk, isFinal = true)
    }

    private fun performRereadRecovery(
        currentChunk: VerifiedChunk,
        expectedHead: ByteArray,
        readExecutor: (lba: Int, sectors: Int) -> ByteArray?
    ): VerifiedChunk {
        var rereadCount = 0
        var bestAvailableChunk = currentChunk
        var previousRereadHead: ByteArray? = null

        while (rereadCount < maxRereads) {
            rereadCount++
            AppLogger.d("OverlapVerifier", "reread_attempt track=$trackNumber lba=${currentChunk.startLba} attempt=$rereadCount")

            val sectorsToRead = currentChunk.endLba - currentChunk.startLba
            val rereadPcm = readExecutor(currentChunk.startLba, sectorsToRead)

            if (rereadPcm != null && rereadPcm.size == currentChunk.pcm.size) {
                val head = extractOverlapHead(rereadPcm)
                val tail = extractOverlapTail(rereadPcm)

                val newChunk = VerifiedChunk(
                    startLba = currentChunk.startLba,
                    endLba = currentChunk.endLba,
                    pcm = rereadPcm,
                    overlapHead = head,
                    overlapTail = tail,
                    confidence = RipConfidence.LOW, // Will upgrade to HIGH if matches
                    rereadCount = rereadCount
                )
                bestAvailableChunk = newChunk

                // Check if this new read matches the expected overlap (from previous chunk's tail)
                if (verifyOverlap(expectedHead, newChunk.overlapHead)) {
                    // We found a read that matches the previous chunk exactly
                    return newChunk.copy(confidence = RipConfidence.HIGH)
                }

                // Check for two consecutive identical rereads
                if (previousRereadHead != null && verifyOverlap(previousRereadHead, newChunk.overlapHead)) {
                    // Two identical rereads match each other. Treat as stable.
                    return newChunk.copy(confidence = RipConfidence.HIGH)
                }

                previousRereadHead = newChunk.overlapHead
            } else {
                 AppLogger.w("OverlapVerifier", "reread_attempt failed to read data track=$trackNumber lba=${currentChunk.startLba} attempt=$rereadCount")
            }
        }

        return bestAvailableChunk.copy(confidence = RipConfidence.LOW)
    }
}
