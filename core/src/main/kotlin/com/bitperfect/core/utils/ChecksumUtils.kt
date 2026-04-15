package com.bitperfect.core.utils

import java.util.zip.CRC32

object ChecksumUtils {
    /**
     * Calculates CRC32 of a byte array.
     */
    fun calculateCrc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /**
     * Calculates CRC32 of a portion of a byte array.
     */
    fun calculateCrc32(data: ByteArray, offset: Int, length: Int): Long {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value
    }
}
