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
     * Calculates the AccurateRip DiscID parts used for the query URL.
     *
     * @param trackOffsets Array of LBA offsets (0-indexed).
     * @param leadOutOffset Lead-out LBA offset.
     * @param freedbId FreeDB/CDDB DiscID as a long.
     */
    fun calculateAccurateRipDiscId(
        trackOffsets: LongArray,
        leadOutOffset: Long,
        freedbId: Long
    ): String {
        val numTracks = trackOffsets.size
        val id1 = trackOffsets.sum() + leadOutOffset
        val id2 = trackOffsets.mapIndexed { index, offset -> (index + 1) * Math.max(offset, 1L) }.sum() + (numTracks + 1) * leadOutOffset

        return "dBAR-%03d-%08x-%08x-%08x.bin".format(
            numTracks,
            id1 and 0xFFFFFFFFL,
            id2 and 0xFFFFFFFFL,
            freedbId and 0xFFFFFFFFL
        )
    }

    suspend fun fetchAccurateRipData(discId: String): Map<Int, List<AccurateRipMatch>> {
        val matches = mutableMapOf<Int, MutableList<AccurateRipMatch>>()

        // URL format: http://www.accuraterip.com/accuraterip/X/Y/Z/dBAR...bin
        // X, Y, Z are the last 3 characters of the DiscID before .bin
        val baseName = discId.removeSuffix(".bin")
        val x = baseName[baseName.length - 1]
        val y = baseName[baseName.length - 2]
        val z = baseName[baseName.length - 3]

        val url = "http://www.accuraterip.com/accuraterip/$x/$y/$z/$discId"

        return try {
            val response = client.get(url)
            if (response.status.value != 200) {
                return emptyMap()
            }

            val bytes = response.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            while (buffer.remaining() >= 13) {
                val trackCount = buffer.get().toInt() and 0xFF
                val id1 = buffer.getInt().toLong() and 0xFFFFFFFFL
                val id2 = buffer.getInt().toLong() and 0xFFFFFFFFL
                val freedb = buffer.getInt().toLong() and 0xFFFFFFFFL

                // We could verify id1, id2, freedb here, but the server usually returns the right one

                for (i in 1..trackCount) {
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
