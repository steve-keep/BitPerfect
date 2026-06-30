package com.bitperfect.plugin.wiim

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import com.bitperfect.core.WiimDebugLogger
import com.bitperfect.core.output.TrackInfo
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

internal class FlacHttpServer(val context: Context, initialTrackList: List<TrackInfo>) : NanoHTTPD(0) {

    var serverIp: String = "127.0.0.1"

    private val trackList = java.util.concurrent.CopyOnWriteArrayList(initialTrackList)

    fun appendTrack(track: TrackInfo) {
        trackList.add(track)
    }

    fun appendTracks(tracks: List<TrackInfo>) {
        trackList.addAll(tracks)
    }

    fun insertTrackAfterIndex(track: TrackInfo, afterIndex: Int) {
        val insertAt = (afterIndex + 1).coerceIn(0, trackList.size)
        trackList.add(insertAt, track)
    }

    fun insertTracksAfterIndex(tracks: List<TrackInfo>, afterIndex: Int) {
        val insertAt = (afterIndex + 1).coerceIn(0, trackList.size)
        trackList.addAll(insertAt, tracks)
    }

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val from = fromIndex.coerceIn(0, trackList.size - 1)
        val to = toIndex.coerceIn(0, trackList.size - 1)
        val track = trackList.removeAt(from)
        trackList.add(to, track)
    }

    fun removeTrack(index: Int) {
        if (index !in trackList.indices) return
        trackList.removeAt(index)
    }

    override fun serve(session: IHTTPSession): Response {
        WiimDebugLogger.log("HTTP ${session.method} ${session.uri}")
        val uri = session.uri

        if (uri.startsWith("/art/") && uri.endsWith(".jpg")) {
            val albumIdStr = uri.substringAfter("/art/").substringBefore(".jpg")
            val albumId = albumIdStr.toLongOrNull() ?: -1L
            if (albumId < 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            val artUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"), albumId
            )
            val stream = try {
                context.contentResolver.openInputStream(artUri)
            } catch (e: Exception) {
                null
            } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            return newChunkedResponse(Response.Status.OK, "image/jpeg", stream)
        }

        if (uri == "/playlist.m3u8") {
            val sb = java.lang.StringBuilder()
            sb.append("#EXTM3U\n")
            trackList.forEach { track ->
                val durationSec = track.durationMs / 1000
                val title = track.title ?: ""
                sb.append("#EXTINF:")
                sb.append(durationSec.toString())
                sb.append(",")
                sb.append(title)
                sb.append("\n")

                val port = this.listeningPort
                sb.append("http://")
                sb.append(serverIp)
                sb.append(":")
                sb.append(port.toString())
                sb.append("/track/")
                sb.append(track.id.toString())
                sb.append(".flac\n")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", sb.toString())
        }

        if (!uri.startsWith("/track/") || !uri.endsWith(".flac")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val trackIdString = uri.substringAfter("/track/").substringBefore(".flac")
        val trackId = trackIdString.toLongOrNull() ?: -1L
        val track = trackList.find { it.id == trackId }
        if (track == null) {
            WiimDebugLogger.log("track 404: id=$trackId not in trackList (size=${trackList.size})")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Track Not Found")
        }

        val filePath = track.filePath ?: track.dataPath
        if (filePath == null) {
            WiimDebugLogger.log("track 404: id=$trackId filePath=null dataPath=${track.dataPath}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Path Not Found")
        }
        val file = File(filePath)
        if (!file.exists()) {
            WiimDebugLogger.log("track 404: id=$trackId file does not exist at $filePath")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")
        }

        val rangeHeader = session.headers["range"]
        val fileLen = file.length()

        var startFrom: Long = 0
        var endAt: Long = -1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).split("-")
            startFrom = try {
                range[0].toLong()
            } catch (e: NumberFormatException) {
                0
            }
            endAt = try {
                if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else -1
            } catch (e: NumberFormatException) {
                -1
            }
        }

        if (rangeHeader != null && startFrom >= 0) {
            if (startFrom >= fileLen) {
                val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                res.addHeader("Content-Range", "bytes 0-0/$fileLen")
                return res
            }

            if (endAt < 0) {
                endAt = fileLen - 1
            }

            var newLen = endAt - startFrom + 1
            if (newLen < 0) {
                newLen = 0
            }

            val fis = FileInputStream(file)
            fis.skip(startFrom)

            WiimDebugLogger.log("track 206: id=$trackId bytes=$startFrom-$endAt/$fileLen")
            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "audio/flac", fis, newLen)
            res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLen")
            return res
        } else {
            WiimDebugLogger.log("track 200: id=$trackId fileLen=$fileLen")
            val fis = FileInputStream(file)
            return newFixedLengthResponse(Response.Status.OK, "audio/flac", fis, fileLen)
        }
    }
}
