package com.bitperfect.app.library

internal data class TrackCandidate(
    val trackId: Long,
    val artist: String,
    val albumTitle: String,
    val albumId: Long,
    val trackTitle: String,
    val bpm: Float?,
    val initialKey: String?,
    val energy: Float?,
    val accurateRipVerified: Boolean,
    val genre: String?,
    val year: String?,
    val tags: List<String>
)
