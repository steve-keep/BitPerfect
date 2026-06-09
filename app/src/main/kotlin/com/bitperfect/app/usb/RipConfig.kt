package com.bitperfect.app.usb

data class RipConfig(
    val chunkSize: Int,
    val overlapSize: Int,
    val driveOffset: Int,
    val tocOffset: Int,
    val sampleOffset: Int,
    val skipBytes: Int
) {
    init {
        require(chunkSize > overlapSize) { "chunkSize must be greater than overlapSize" }
    }

    companion object {
        fun from(driveOffset: Int, chunkSize: Int = 16, overlapSize: Int = 6): RipConfig {
            var toc = driveOffset / 588
            var rem = driveOffset % 588
            if (rem < 0) { rem += 588; toc-- }
            return RipConfig(
                chunkSize = chunkSize,
                overlapSize = overlapSize,
                driveOffset = driveOffset,
                tocOffset = toc,
                sampleOffset = rem,
                skipBytes = rem * 4
            )
        }
    }
}
