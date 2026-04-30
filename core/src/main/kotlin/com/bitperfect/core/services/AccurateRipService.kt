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
        return buildAccurateRipUrl(discId)
    }

    suspend fun checkIsKeyDisc(toc: DiscToc): Boolean = withContext(Dispatchers.IO) {
        try {
            val discId = computeAccurateRipDiscId(toc)
            val url = buildAccurateRipUrl(discId)

            val hexId1 = String.format("%08x", discId.id1 and 0xFFFFFFFFL)
            val hexId2 = String.format("%08x", discId.id2 and 0xFFFFFFFFL)
            val hexId3 = String.format("%08x", discId.id3 and 0xFFFFFFFFL)
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

    private fun buildAccurateRipUrl(discId: AccurateRipDiscId): String {
        // Last 3 hex nibbles of disc_id_1
        val hexId1 = String.format("%08x", discId.id1 and 0xFFFFFFFFL)
        val len = hexId1.length

        // Ensure we have at least 3 characters
        val safeHex = if (len >= 3) hexId1 else hexId1.padStart(8, '0')
        val a = safeHex[safeHex.length - 1]
        val b = safeHex[safeHex.length - 2]
        val c = safeHex[safeHex.length - 3]

        val hexId2 = String.format("%08x", discId.id2 and 0xFFFFFFFFL)
        val hexId3 = String.format("%08x", discId.id3 and 0xFFFFFFFFL)

        return "http://www.accuraterip.com/accuraterip/$a/$b/$c/dAR$safeHex-$hexId2-$hexId3.bin"
    }
}
