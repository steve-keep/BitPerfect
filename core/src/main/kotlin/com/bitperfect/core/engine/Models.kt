package com.bitperfect.core.engine

import android.hardware.usb.UsbDevice

sealed class BitPerfectDrive {
    abstract val name: String
    abstract val manufacturer: String?
    abstract val identifier: String

    data class Physical(val device: UsbDevice) : BitPerfectDrive() {
        override val name: String = device.productName ?: "Unknown Drive"
        override val manufacturer: String? = device.manufacturerName
        override val identifier: String = device.deviceName
    }

    data class Virtual(val id: Int, val vendor: String, val product: String) : BitPerfectDrive() {
        override val name: String = "$vendor $product".trim()
        override val manufacturer: String = vendor.trim()
        override val identifier: String = "virtual_$id"
    }
}

data class TestCd(
    val artist: String,
    val album: String,
    val tracks: List<String>,
    val firstTrack: Int = 1,
    val lastTrack: Int = tracks.size,
    val customTrackOffsets: IntArray? = null,
    val accurateRipId1: UInt? = null,
    val accurateRipId2: UInt? = null,
    val cddbId: Int? = null,
    val trackCrcsV1: IntArray? = null,
    val trackCrcsV2: IntArray? = null,
    val confidence: IntArray? = null
) {
    val trackOffsets: IntArray by lazy {
        if (customTrackOffsets != null) return@lazy customTrackOffsets

        val offsets = IntArray(100)
        // Simulate tracks of varying lengths (approx 3-5 mins = 13500-22500 sectors)
        var currentOffset = 150 // Standard Pregap
        for (i in 1..lastTrack) {
            offsets[i] = currentOffset
            currentOffset += 15000 + (i * 100) // Deterministic length
        }
        offsets[0] = currentOffset // Lead-out
        offsets
    }
}

data class TrackEntry(
    val number: Int,
    val startLba: Int,
    val durationSectors: Int,
    val isAudio: Boolean
)

data class DiscToc(
    val firstTrack: Int,
    val lastTrack: Int,
    val tracks: List<TrackEntry>,
    val leadOutLba: Int,
    val totalDurationSectors: Int
) {
    val trackCount: Int get() = tracks.size
}

enum class SectorResult {
    SUCCESS,
    SUSPICIOUS
}

enum class ErrorRecoveryQuality(val maxBatches: Int, val label: String) {
    LOW(1, "Low (1 batch)"),
    MEDIUM(3, "Medium (3 batches)"),
    HIGH(5, "High (5 batches)")
}

data class ExtractedSector(
    val result: SectorResult,
    val data: ByteArray
)
