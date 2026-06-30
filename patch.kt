// This script will read FlacHttpServer.kt, split it into parts, insert the correct code block and write it back.
import java.io.File

fun main() {
    val file = File("./plugin-wiim/src/main/kotlin/com/bitperfect/plugin/wiim/FlacHttpServer.kt")
    val content = file.readText()

    val oldTarget = "        if (!uri.startsWith(\"/track/\") || !uri.endsWith(\".flac\")) {"

    val newBlock = """        if (uri == "/playlist.m3u8") {
            val sb = java.lang.StringBuilder()
            sb.append("#EXTM3U\n")
            trackList.forEach { track ->
                val durationSec = track.durationMs / 1000
                val title = track.title ?: ""
                sb.append("#EXTINF:")
                sb.append(durationSec)
                sb.append(",")
                sb.append(title)
                sb.append("\n")

                val port = this.listeningPort
                sb.append("http://")
                sb.append(serverIp)
                sb.append(":")
                sb.append(port)
                sb.append("/track/")
                sb.append(track.id)
                sb.append(".flac\n")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", sb.toString())
        }

        if (!uri.startsWith("/track/") || !uri.endsWith(".flac")) {"""

    val newContent = content.replace(oldTarget, newBlock)
    file.writeText(newContent)
}
