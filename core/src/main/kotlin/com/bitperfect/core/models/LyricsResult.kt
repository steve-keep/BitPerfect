package com.bitperfect.core.models

data class LyricsResult(
    val plainLyrics: String?,   // LYRICS Vorbis tag
    val syncedLyrics: String?   // SYNCEDLYRICS Vorbis tag (LRC format)
)
