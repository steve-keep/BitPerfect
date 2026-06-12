package com.bitperfect.core.output

/**
 * Snapshot of playback state handed to an [OutputPlugin] when it becomes
 * the active output target.
 *
 * The plugin is responsible for:
 *  - restoring the queue ([tracks])
 *  - seeking to [currentIndex] and [positionMs]
 *  - honouring [playWhenReady]
 */
data class PlaybackHandoffState(
    val tracks: List<CoreTrackInfo>,
    val currentIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)
