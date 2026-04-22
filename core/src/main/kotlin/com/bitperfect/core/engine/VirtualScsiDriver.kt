package com.bitperfect.core.engine

import com.bitperfect.driver.IScsiDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VirtualScsiDriver(var testCd: TestCd) : IScsiDriver {
    private var isTrayOpen: Boolean = false

    override fun getDriverVersion(): String = "Virtual-1.0"

    override fun initDevice(
        fd: Int,
        interfaceNumber: Int,
        endpointIn: Int,
        endpointOut: Int
    ): Boolean {
        return true
    }

    override fun executeScsiCommand(
        fd: Int,
        command: ByteArray,
        expectedResponseLength: Int,
        endpointIn: Int,
        endpointOut: Int,
        timeout: Int
    ): ByteArray? {
        if (command.isEmpty()) return null
        val opcode = command[0].toInt() and 0xFF

        // If tray is open, most commands should return null (Check Condition) except these
        if (isTrayOpen && opcode != 0x03 && opcode != 0x1B && opcode != 0x12) {
            return null
        }

        return when (opcode) {
            0x00 -> handleTestUnitReady()
            0x03 -> handleRequestSense(expectedResponseLength)
            0x12 -> handleInquiry(expectedResponseLength)
            0x1B -> handleStartStopUnit(command)
            0x28 -> handleRead10(command, expectedResponseLength)
            0x46 -> handleGetConfiguration(expectedResponseLength)
            0x5A -> handleModeSense10(expectedResponseLength)
            0x43 -> handleReadToc(command, expectedResponseLength)
            0xBE -> handleReadCd(command, expectedResponseLength)
            else -> ByteArray(expectedResponseLength) // Dummy response for unsupported commands
        }
    }

    private fun handleTestUnitReady(): ByteArray? {
        return if (isTrayOpen) null else ByteArray(0)
    }

    private fun handleRequestSense(length: Int): ByteArray {
        val response = ByteArray(length.coerceAtLeast(18))
        if (isTrayOpen) {
            response[0] = 0x70.toByte() // Current errors
            response[2] = 0x02.toByte() // Sense Key: NOT READY
            response[7] = 10           // Additional Sense Length
            response[12] = 0x3A.toByte() // ASC: MEDIUM NOT PRESENT
            response[13] = 0x00.toByte() // ASCQ
        } else {
            response[0] = 0x70.toByte()
            response[2] = 0x00.toByte() // NO SENSE
        }
        return response.take(length).toByteArray()
    }

    private fun handleStartStopUnit(command: ByteArray): ByteArray {
        val loej = (command[4].toInt() and 0x02) != 0
        val start = (command[4].toInt() and 0x01) != 0

        if (loej) {
            isTrayOpen = !start
        }

        return ByteArray(0)
    }

    private fun handleInquiry(length: Int): ByteArray {
        val response = ByteArray(length.coerceAtLeast(36))
        // Peripheral Device Type: 0x05 (CD-ROM)
        response[0] = 0x05

        // Vendor: "ASUS    "
        "ASUS    ".toByteArray().copyInto(response, 8)
        // Product: "DRW-24B1ST   a  "
        "DRW-24B1ST   a  ".toByteArray().copyInto(response, 16)
        // Revision: "1.00"
        "1.00".toByteArray().copyInto(response, 32)

        return response.take(length).toByteArray()
    }

    private fun handleModeSense10(length: Int): ByteArray {
        val response = ByteArray(length.coerceAtLeast(30))
        // Simulating C2 support in Read Capabilities page (0x2A)
        // This is a bit simplified, but RippingEngine checks modeSenseResponse[10] & 0x01
        response[10] = 0x01 // C2 supported
        return response.take(length).toByteArray()
    }

    private fun handleReadToc(command: ByteArray, length: Int): ByteArray {
        val msf = (command[1].toInt() and 0x02) != 0
        val format = command[2].toInt() and 0x0F
        val response = ByteArray(length)
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)

        if (format == 0) { // Standard TOC
            // TOC Data Length
            buffer.putShort(0, (4 + 8 * (testCd.lastTrack - testCd.firstTrack + 2) - 2).toShort())
            buffer.put(2, testCd.firstTrack.toByte())
            buffer.put(3, testCd.lastTrack.toByte())

            for (i in 0..(testCd.lastTrack - testCd.firstTrack + 1)) {
                val base = 4 + i * 8
                if (base + 8 > length) break

                val trackNum = if (i <= (testCd.lastTrack - testCd.firstTrack)) {
                    testCd.firstTrack + i
                } else {
                    0xAA.toInt() // Lead-out
                }

                buffer.put(base + 1, 0x14) // ADR/Control (Data/Audio)
                buffer.put(base + 2, trackNum.toByte())

                val absoluteSector = if (trackNum == 0xAA) testCd.trackOffsets[0] else testCd.trackOffsets[trackNum]
                val lba = absoluteSector - 150

                if (msf) {
                    val m = absoluteSector / 4500
                    val s = (absoluteSector / 75) % 60
                    val f = absoluteSector % 75
                    buffer.put(base + 5, m.toByte())
                    buffer.put(base + 6, s.toByte())
                    buffer.put(base + 7, f.toByte())
                } else {
                    buffer.putInt(base + 4, lba)
                }
            }
        }
        return response
    }

    private fun handleGetConfiguration(length: Int): ByteArray {
        val response = ByteArray(length.coerceAtLeast(32))
        // Feature Header (8 bytes)
        // Data length: let's say 24 bytes (so 0, 0, 0, 24)
        response[3] = 24

        // Feature 1: CD Read (0x0107)
        response[8] = 0x01
        response[9] = 0x07
        response[11] = 4 // Additional length
        response[12] = 0x02 // Bit 1 = AccurateStream

        // Feature 2: C2 Error Pointers (0x0014)
        response[16] = 0x00
        response[17] = 0x14
        response[19] = 4 // Additional length
        response[20] = 0x01 // Bit 0 = C2 Error Pointers

        return response.take(length).toByteArray()
    }

    private var lastReadStartLba = -1
    private var lastReadEndLba = -1

    private fun handleRead10(command: ByteArray, length: Int): ByteArray {
        val lba = ((command[2].toInt() and 0xFF) shl 24) or
                  ((command[3].toInt() and 0xFF) shl 16) or
                  ((command[4].toInt() and 0xFF) shl 8) or
                  (command[5].toInt() and 0xFF)
        val sectorCount = ((command[7].toInt() and 0xFF) shl 8) or
                          (command[8].toInt() and 0xFF)

        val response = ByteArray(length)
        val bytesPerSector = length / sectorCount.coerceAtLeast(1)

        for (s in 0 until sectorCount) {
            val offset = s * bytesPerSector
            val currentLba = lba + s
            for (i in 0 until bytesPerSector.coerceAtMost(2352)) {
                if (offset + i < response.size) {
                    response[offset + i] = ((currentLba + i) % 256).toByte()
                }
            }
        }

        return response
    }

    private fun handleReadCd(command: ByteArray, length: Int): ByteArray {
        val lba = ((command[2].toInt() and 0xFF) shl 24) or
                  ((command[3].toInt() and 0xFF) shl 16) or
                  ((command[4].toInt() and 0xFF) shl 8) or
                  (command[5].toInt() and 0xFF)

        val sectorCount = ((command[6].toInt() and 0xFF) shl 16) or
                          ((command[7].toInt() and 0xFF) shl 8) or
                          (command[8].toInt() and 0xFF)

        val actualSectorCount = if (sectorCount == 0) 1 else sectorCount

        // Simulate cache
        // If it's a re-read of the exact same block or a sequential read, treat it as a cache hit
        if (lba == lastReadStartLba || lba == lastReadEndLba + 1) {
            // Cache hit, fast response (no sleep)
        } else {
            // Cache miss, slow response
            Thread.sleep(10)
        }
        lastReadStartLba = lba
        lastReadEndLba = lba + actualSectorCount - 1

        val bytesPerSector = length / actualSectorCount

        // Deterministic dummy PCM data
        val response = ByteArray(length)

        for (s in 0 until actualSectorCount) {
            val offset = s * bytesPerSector
            val currentLba = lba + s
            for (i in 0 until bytesPerSector.coerceAtMost(2352)) {
                if (offset + i < response.size) {
                    response[offset + i] = ((currentLba + i) % 256).toByte()
                }
            }

            // If C2 is requested (bytesPerSector > 2352), fill C2 area with zeros (no errors)
            if (bytesPerSector > 2352) {
                for (i in 2352 until bytesPerSector) {
                    if (offset + i < response.size) {
                        response[offset + i] = 0
                    }
                }
            }
        }

        return response
    }
}
