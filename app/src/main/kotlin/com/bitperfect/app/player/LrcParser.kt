package com.bitperfect.app.player

data class LrcLine(val timestampMs: Long, val text: String)

fun parseLrc(raw: String): List<LrcLine> {
    val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2})\](.*)""")
    return raw.lines()
        .mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val (minStr, secStr, fracStr, textPart) = match.destructured
            val text = textPart.trim()

            if (text.isBlank()) return@mapNotNull null

            val min = minStr.toLong()
            val sec = secStr.toLong()
            val hundredths = fracStr.toLong()
            val timestampMs = min * 60_000 + sec * 1_000 + hundredths * 10

            LrcLine(timestampMs, text)
        }
        .sortedBy { it.timestampMs }
}
