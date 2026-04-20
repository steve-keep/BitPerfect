package com.bitperfect.core.engine

import com.bitperfect.core.utils.MusicBrainzUtils
import com.bitperfect.core.engine.AccurateRipService

data class AccurateRipDiscId(
    val id1: UInt,
    val id2: UInt,
    val id3: UInt
)

fun DiscToc.computeFreedbId(): Long {
    val trackOffsets = this.tracks.map { it.startLba.toLong() + 150L }.toLongArray()
    val leadOutOffset = this.leadOutLba.toLong() + 150L
    return MusicBrainzUtils.calculateFreeDbId(trackOffsets, leadOutOffset)
}

fun DiscToc.computeAccurateRipId(): AccurateRipDiscId {
    val trackOffsets = this.tracks.map { Math.max(0L, it.startLba.toLong()) }.toLongArray()
    val leadOutOffset = Math.max(0L, this.leadOutLba.toLong())
    val freedbId = this.computeFreedbId()

    val numTracks = trackOffsets.size
    val id1 = trackOffsets.sum() + leadOutOffset
    val id2 = trackOffsets.mapIndexed { index, offset -> (index + 1) * Math.max(offset, 1L) }.sum() + (numTracks + 1) * leadOutOffset
    val id3 = freedbId

    return AccurateRipDiscId((id1 and 0xFFFFFFFFL).toUInt(), (id2 and 0xFFFFFFFFL).toUInt(), (id3 and 0xFFFFFFFFL).toUInt())
}

fun DiscToc.computeMusicBrainzId(): String {
    val offsets = IntArray(100)
    offsets[0] = this.leadOutLba // Not adding 150 here, because calculateDiscId will add 150
    this.tracks.forEach {
        if (it.number in 1..99) {
            offsets[it.number] = it.startLba
        }
    }
    return MusicBrainzUtils.calculateDiscId(this.firstTrack, this.lastTrack, offsets)
}
