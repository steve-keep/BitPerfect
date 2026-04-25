package com.bitperfect.core.models

data class TrackInfo(
    val number: Int,       // 1–99
    val lba: Int,          // absolute sector address; (M*60+S)*75+F-150
    val isAudio: Boolean   // ADR/Control byte bit 2 == 0
)

data class DiscToc(
    val firstTrack: Int,
    val lastTrack: Int,
    val tracks: List<TrackInfo>,  // does NOT include lead-out
    val leadOutLba: Int,          // total sectors on disc
    val audioTrackCount: Int = tracks.count { it.isAudio }
)
