package com.bitperfect.core.engine

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AccurateRipMatch(
    val confidence: Int,
    val crc: Long,
    val crc2: Long
)

class AccurateRipService {
    private val client = HttpClient(OkHttp)

    /**
     * Generates the AccurateRip query URL string from an AccurateRipDiscId.
     *
     * @param trackCount Number of tracks.
     * @param discId AccurateRipDiscId computed from DiscToc.
     */
    fun generateAccurateRipUrlName(
        trackCount: Int,
        discId: AccurateRipDiscId
    ): String {
        return "dBAR-%03d-%08x-%08x-%08x.bin".format(
            trackCount,
            discId.id1.toLong(),
            discId.id2.toLong(),
            discId.id3.toLong()
        )
    }

    suspend fun fetchAccurateRipData(trackCount: Int, discId: AccurateRipDiscId, onLog: ((String) -> Unit)? = null): Map<Int, List<AccurateRipMatch>> {
        val matches = mutableMapOf<Int, MutableList<AccurateRipMatch>>()

        val discIdName = generateAccurateRipUrlName(trackCount, discId)

        // URL format: http://www.accuraterip.com/accuraterip/X/Y/Z/dBAR...bin
        // X, Y, Z are the last 3 characters of the DiscID1, not the whole discIdName which ends in CDDB ID!
        val id1Hex = "%08x".format(discId.id1.toLong())
        val x = id1Hex[id1Hex.length - 1]
        val y = id1Hex[id1Hex.length - 2]
        val z = id1Hex[id1Hex.length - 3]

        val url = "http://www.accuraterip.com/accuraterip/$x/$y/$z/$discIdName"

        onLog?.invoke("Fetching AccurateRip data from $url")
        return try {
            val response = client.get(url)
            onLog?.invoke("AccurateRip response: ${response.status.value}")
            if (response.status.value != 200) {
                return emptyMap()
            }

            val bytes = response.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.remaining() >= 13) {
                val returnedTrackCount = buffer.get().toInt() and 0xFF
                // We read but don't strictly need to verify id1, id2, freedb, so we skip allocating unused variables
                buffer.position(buffer.position() + 12)

                // We could verify id1, id2, freedb here, but the server usually returns the right one

                for (i in 1..returnedTrackCount) {
                    if (buffer.remaining() < 9) break
                    val confidence = buffer.get().toInt() and 0xFF
                    val crc = buffer.getInt().toLong() and 0xFFFFFFFFL
                    val crc2 = buffer.getInt().toLong() and 0xFFFFFFFFL

                    matches.getOrPut(i) { mutableListOf() }.add(AccurateRipMatch(confidence, crc, crc2))
                }
            }
            matches
        } catch (e: Exception) {
            Log.e("AccurateRipService", "Error fetching AccurateRip data: ${e.message}")
            emptyMap()
        }
    }
}
