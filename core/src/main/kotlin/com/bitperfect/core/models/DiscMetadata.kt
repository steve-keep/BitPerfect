package com.bitperfect.core.models

data class DiscMetadata(
    val albumTitle: String,
    val artistName: String,
    val trackTitles: List<String>,
    val mbReleaseId: String,
    val year: String? = null,
    val genre: String? = null,
    val albumArtist: String? = null
)
