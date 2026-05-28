package com.bitperfect.app.ripping.paranoia.strategy

data class ChunkReductionPolicy(
    val minimumChunkSize: Int = 8,
    val maxReductionDepth: Int = 2
)
