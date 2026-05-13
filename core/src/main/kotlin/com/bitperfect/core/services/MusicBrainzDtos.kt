package com.bitperfect.core.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class MbDiscIdResponse(val releases: List<MbRelease> = emptyList())

@Serializable data class MbRelease(
    val id: String,
    val title: String,
    val date: String? = null,
    val genres: List<MbGenre> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val media: List<MbMedia> = emptyList()
)

@Serializable data class MbArtistCredit(
    val name: String? = null,
    val joinphrase: String? = null,
    val artist: MbArtist
)

@Serializable data class MbArtist(
    val name: String,
    val genres: List<MbGenre> = emptyList()
)

@Serializable data class MbGenre(
    val name: String
)

@Serializable data class MbDisc(val id: String)

@Serializable data class MbMedia(
    val position: Int? = null,
    val tracks: List<MbTrack> = emptyList(),
    val discs: List<MbDisc> = emptyList()
)

@Serializable data class MbTrack(val title: String)
