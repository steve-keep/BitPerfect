package com.bitperfect.core.engine

import com.bitperfect.driver.IScsiDriver

class CdSectorReader(
    private val scsiDriver: IScsiDriver,
    private val fd: Int,
    private val endpointIn: Int = 0x81,
    private val endpointOut: Int = 0x01
) {

    fun readSectors(
        lba: Long,
        sectorCount: Int = 1,
        includeC2: Boolean = false,
        includeSubchannel: Boolean = false
    ): ByteArray? {
        val bytesPerSector = 2352 + (if (includeC2) 294 else 0) + (if (includeSubchannel) 96 else 0)
        val expectedLength = bytesPerSector * sectorCount

        var byte9 = 0x10 // User data only
        if (includeC2) {
            byte9 = byte9 or 0x02 // C2 Error pointers
        }

        var byte10 = 0x00
        if (includeSubchannel) {
            byte10 = 0x01 // RAW P-W Sub-channel
        }

        val readCdCmd = byteArrayOf(
            0xBE.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),
            ((sectorCount shr 16) and 0xFF).toByte(),
            ((sectorCount shr 8) and 0xFF).toByte(),
            (sectorCount and 0xFF).toByte(),
            byte9.toByte(),
            byte10.toByte(),
            0
        )

        var result = scsiDriver.executeScsiCommand(fd, readCdCmd, expectedLength, endpointIn, endpointOut)

        if (result == null) {
            // Fallback to READ(10) (0x28)
            // READ(10) returns 2352 bytes of User Data per sector
            val read10Cmd = byteArrayOf(
                0x28.toByte(), 0,
                ((lba shr 24) and 0xFF).toByte(),
                ((lba shr 16) and 0xFF).toByte(),
                ((lba shr 8) and 0xFF).toByte(),
                (lba and 0xFF).toByte(),
                0,
                ((sectorCount shr 8) and 0xFF).toByte(),
                (sectorCount and 0xFF).toByte(),
                0
            )

            val read10Length = 2352 * sectorCount
            val read10Result = scsiDriver.executeScsiCommand(fd, read10Cmd, read10Length, endpointIn, endpointOut)

            if (read10Result != null) {
                if (!includeC2 && !includeSubchannel) {
                    return read10Result
                }

                // Pad missing C2 and Subchannel data with zeros
                val paddedResult = ByteArray(expectedLength)
                for (i in 0 until sectorCount) {
                    val srcOffset = i * 2352
                    val dstOffset = i * bytesPerSector
                    System.arraycopy(read10Result, srcOffset, paddedResult, dstOffset, 2352)
                    // The rest is automatically padded with zeros
                }
                return paddedResult
            }
        }

        return result
    }
}
