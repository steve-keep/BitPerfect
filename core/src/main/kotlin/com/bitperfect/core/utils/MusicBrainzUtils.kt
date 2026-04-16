package com.bitperfect.core.utils

import java.security.MessageDigest
import android.util.Base64

object MusicBrainzUtils {

    /**
     * Calculates the FreeDB/CDDB DiscID.
     *
     * @param trackOffsets Array of LBA offsets (0-indexed).
     * @param leadOutOffset Lead-out LBA offset.
     */
    fun calculateFreeDbId(trackOffsets: LongArray, leadOutOffset: Long): Long {
        var sum = 0L
        for (offset in trackOffsets) {
            var n = offset / 75
            while (n > 0) {
                sum += n % 10
                n /= 10
            }
        }
        val totalTime = (leadOutOffset - trackOffsets[0]) / 75
        val discId = ((sum % 0xFF) shl 24) or (totalTime shl 8) or trackOffsets.size.toLong()
        return discId and 0xFFFFFFFFL
    }

    /**
     * Calculates the MusicBrainz DiscID from TOC data.
     *
     * @param firstTrack The first track number (usually 1).
     * @param lastTrack The last track number.
     * @param offsets Array of LBA offsets for each track (1-indexed, size should be 100).
     *                offsets[0] is the lead-out LBA.
     *                offsets[1..lastTrack] are the start LBAs for each track.
     */
    fun calculateDiscId(firstTrack: Int, lastTrack: Int, offsets: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-1")

        // Format: FirstTrack (2 hex) LastTrack (2 hex) LeadOut (8 hex) Track1 (8 hex) ... Track99 (8 hex)
        // All values are LBA + 150

        val sb = StringBuilder()
        sb.append("%02X".format(firstTrack))
        sb.append("%02X".format(lastTrack))
        sb.append("%08X".format(offsets[0] + 150))

        for (i in 1..99) {
            if (i <= lastTrack) {
                sb.append("%08X".format(offsets[i] + 150))
            } else {
                sb.append("%08X".format(0))
            }
        }

        val hash = digest.digest(sb.toString().toByteArray(Charsets.US_ASCII))

        // Base64 encode and replace characters
        // Standard Base64: + / =
        // MusicBrainz Base64: . _ -
        return Base64.encodeToString(hash, Base64.NO_WRAP)
            .replace('+', '.')
            .replace('/', '_')
            .replace('=', '-')
    }
}
