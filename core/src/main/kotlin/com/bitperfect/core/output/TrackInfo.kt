package com.bitperfect.core.output

data class TrackInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val durationMs: Long,
    val trackNumber: Int,
    val filePath: String?,
    val dataPath: String?,
    val albumId: Long,
    val discNumber: Int = 1,
    val isAccurateRipVerified: Boolean = false,
)


/** Backward-compat alias. Remove once all callers use TrackInfo. */
@Deprecated("Use TrackInfo directly", ReplaceWith("TrackInfo"))
typealias CoreTrackInfo = TrackInfo
