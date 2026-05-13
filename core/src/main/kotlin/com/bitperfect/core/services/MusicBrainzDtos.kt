package com.bitperfect.core.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class MbDiscIdResponse(val releases: List<MbRelease> = emptyList())

@Serializable data class MbRelease(
    val id: String,
    val title: String,
    val date: String? = null,
    val barcode: String? = null,
    val genres: List<MbGenre> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val media: List<MbMedia> = emptyList(),
    @SerialName("cover-art-archive") val coverArtArchive: MbCoverArtArchive? = null
)

@Serializable data class MbCoverArtArchive(val front: Boolean = false)

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
    val discs: List<MbDisc> = emptyList(),
    @SerialName("track-count") val trackCount: Int = 0
)

@Serializable data class MbTrack(
    val title: String,
    val number: String? = null
)
