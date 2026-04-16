package com.bitperfect.core.engine

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackRipResult(
    val trackNumber: Int,
    val status: String,
    val reReads: Int,
    val crc32: Long,
    val accurateRipCrc: Long,
    val accurateRipStatus: String
)

data class RipSessionInfo(
    val appVersion: String,
    val date: Date,
    val driveModel: String,
    val capabilities: List<String>,
    val albumMetadata: AlbumMetadata,
    val trackResults: List<TrackRipResult>
)

object LogGenerator {
    fun generateLog(info: RipSessionInfo): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.append("BitPerfect Rip Log\n")
        sb.append("==================\n\n")
        sb.append("App Version    : ${info.appVersion}\n")
        sb.append("Date           : ${dateFormat.format(info.date)}\n")
        sb.append("Drive Model    : ${info.driveModel}\n")
        sb.append("Capabilities   : ${info.capabilities.joinToString(", ")}\n\n")

        sb.append("Metadata\n")
        sb.append("--------\n")
        sb.append("Artist         : ${info.albumMetadata.artist}\n")
        sb.append("Album          : ${info.albumMetadata.album}\n")
        sb.append("Year           : ${info.albumMetadata.year}\n\n")

        sb.append("Track Details\n")
        sb.append("-------------\n")
        for (track in info.trackResults) {
            sb.append("Track %02d\n".format(track.trackNumber))
            sb.append("  Status       : ${track.status}\n")
            sb.append("  Re-reads     : ${track.reReads}\n")
            sb.append("  CRC32        : %08X\n".format(track.crc32))
            sb.append("  AccurateRip  : %08X\n".format(track.accurateRipCrc))
            sb.append("  Verification : ${track.accurateRipStatus}\n\n")
        }

        sb.append("Summary\n")
        sb.append("-------\n")
        val accurateCount = info.trackResults.count { it.accurateRipStatus.startsWith("Accurate") }
        sb.append("Tracks accurately ripped: $accurateCount / ${info.trackResults.size}\n")

        return sb.toString()
    }

    fun saveLogToFile(path: String, content: String) {
        File(path).writeText(content)
    }
}
