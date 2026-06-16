package com.bitperfect.core.output

data class CoreTrackInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val durationMs: Long,
    val trackNumber: Int,
    val filePath: String?,
    val dataPath: String?,
    val albumId: Long,
)
