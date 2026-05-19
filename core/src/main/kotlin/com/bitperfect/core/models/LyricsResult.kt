package com.bitperfect.core.models

data class LyricsResult(
    val plainLyrics: String?,   // LYRICS Vorbis tag
    val syncedLyrics: String?   // SYNCEDLYRICS Vorbis tag (LRC format)
)

sealed class LyricsFetchResult {
    data class Success(
        val lyrics: LyricsResult
    ) : LyricsFetchResult()

    data class Failure(
        val state: FetchState,
        val message: String? = null,
        val httpCode: Int? = null,
        val throwable: Throwable? = null
    ) : LyricsFetchResult()
}

enum class FetchState {
    NOT_FOUND,
    HTTP_ERROR,
    NETWORK_ERROR,
    TIMEOUT,
    CANCELLED,
    JSON_PARSE_ERROR,
    EMPTY_HTTP_BODY,
    NO_LYRICS,
    INVALID_RESPONSE,
    UNKNOWN_ERROR
}
