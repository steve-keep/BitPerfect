package com.bitperfect.core.output

/**
 * Minimal track descriptor used in [PlaybackHandoffState].
 * This exists so :core does not depend on :app's TrackInfo.
 * Once TrackInfo is moved to :core in a later phase, this class
 * will be replaced by the canonical TrackInfo.
 */
data class CoreTrackInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val durationMs: Long,
    val trackNumber: Int,
    /** Absolute path to the FLAC or audio file on device storage. */
    val filePath: String?,
    /** Alternative data path (e.g. ripped file before MediaStore scan). */
    val dataPath: String?,
    /** Album ID for artwork lookup. -1 if unavailable. */
    val albumId: Long,
)
