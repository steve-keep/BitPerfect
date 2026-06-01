package com.bitperfect.app.ripping.pipeline

/**
 * Raw PCM data as read directly off the USB bus, before any overlap
 * verification or FLAC encoding. The [pcmData] array contains exactly
 * [sectorCount] * 2352 bytes.
 */
data class RawChunk(
    val startLba: Int,
    val sectorCount: Int,
    val pcmData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawChunk
        if (startLba != other.startLba) return false
        if (sectorCount != other.sectorCount) return false
        if (!pcmData.contentEquals(other.pcmData)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = startLba
        result = 31 * result + sectorCount
        result = 31 * result + pcmData.contentHashCode()
        return result
    }
}

/**
 * Control signals sent from the consumer (AudioProcessingPipeline) back to
 * the producer (UsbReadWorker) to coordinate sequential reads and recovery
 * windows.
 */
sealed class ReadCommand {
    /**
     * Instruct the producer to begin (or resume) sequential reads from
     * [startLba] up to (but not including) [endLba], issuing [chunkSize]
     * sectors per USB request.
     */
    data class StartSequential(
        val startLba: Int,
        val endLba: Int,
        val chunkSize: Int
    ) : ReadCommand()

    /**
     * Instruct the producer to halt immediately and discard any sectors
     * currently buffered in the channel. Sent by the consumer when overlap
     * verification fails and targeted re-reads must take priority.
     */
    object SuspendAndFlush : ReadCommand()
}

/**
 * Immutable configuration for a single-track rip pipeline run.
 *
 * @param chunkSize   Number of sectors per sequential USB read request.
 * @param overlapSize Number of overlap sectors used for paranoia verification.
 *                    Must be less than [chunkSize].
 * @param channelCapacity Bounded capacity of the [kotlinx.coroutines.channels.Channel]
 *                        connecting producer and consumer. Sized to buffer
 *                        approximately 18 seconds of audio at the default chunk size.
 */
data class RipPipelineConfig(
    val chunkSize: Int = 27,
    val overlapSize: Int = 6,
    val channelCapacity: Int = 50
) {
    init {
        require(chunkSize > overlapSize) {
            "chunkSize ($chunkSize) must be greater than overlapSize ($overlapSize)"
        }
        require(channelCapacity > 0) {
            "channelCapacity must be positive"
        }
    }
}