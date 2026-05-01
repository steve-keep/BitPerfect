package com.bitperfect.core.services

import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.core.utils.AccurateRipDiscId
import com.bitperfect.core.utils.computeAccurateRipDiscId
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AccurateRipService(private val client: AccurateRipClient = AccurateRipClient()) {

    companion object {
        private const val TAG = "AccurateRipService"
    }

    fun getAccurateRipUrl(toc: DiscToc): String {
        val discId = computeAccurateRipDiscId(toc)
        return buildAccurateRipUrl(discId.id1, discId.id2, discId.id3, toc.trackCount)
    }

    suspend fun checkIsKeyDisc(toc: DiscToc): Boolean = withContext(Dispatchers.IO) {
        try {
            val discId = computeAccurateRipDiscId(toc)
            val url = buildAccurateRipUrl(discId.id1, discId.id2, discId.id3, toc.trackCount)

            val hexId1 = String.format("%08x", discId.id1 and 0xFFFFFFFFL)
            val hexId2 = String.format("%08x", discId.id2 and 0xFFFFFFFFL)
            val hexId3 = String.format("%08x", discId.id3)
            AppLogger.d(TAG, "Raw hex IDs - id1: $hexId1, id2: $hexId2, id3: $hexId3, URL: $url")

            val response = client.fetchBin(url)

            if (response.status == HttpStatusCode.OK) {
                // For now, if we get a 200 OK, we consider it a Key Disc
                // Further parsing would go here if we need to extract expected checksums
                return@withContext true
            } else {
                AppLogger.d(TAG, "AccurateRip returned status ${response.status} for URL $url")
                return@withContext false
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking AccurateRip: ${e.message}")
            return@withContext false
        }
    }

    private fun buildAccurateRipUrl(id1: Long, id2: Long, cddb: Long, trackCount: Int): String {
        val hexId1 = String.format("%08x", id1 and 0xFFFFFFFFL)

        // Extract the last 3 characters in reverse order for the directory path
        val c1 = hexId1[7]
        val c2 = hexId1[6]
        val c3 = hexId1[5]

        val hexId2 = String.format("%08x", id2 and 0xFFFFFFFFL)
        val hexCddb = String.format("%08x", cddb)
        val trackCountStr = String.format("%03d", trackCount)

        return "http://www.accuraterip.com/accuraterip/$c1/$c2/$c3/dBAR-$trackCountStr-$hexId1-$hexId2-$hexCddb.bin"
    }
}
