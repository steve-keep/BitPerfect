package com.bitperfect.core.virtual

import com.bitperfect.driver.IScsiDriver
import kotlin.experimental.and

data class TestCd(
    val vendor: String,
    val product: String,
    val revision: String,
    val tracks: List<TestTrack>
)

data class TestTrack(
    val startLba: Long,
    val endLba: Long
)

class VirtualScsiDriver(private val testCdProvider: () -> TestCd) : IScsiDriver {
    override fun getDriverVersion(): String = "Virtual-1.0.0"

    override fun executeScsiCommand(
        fd: Int,
        command: ByteArray,
        expectedResponseLength: Int,
        endpointIn: Int,
        endpointOut: Int,
        timeout: Int
    ): ByteArray? {
        if (command.isEmpty()) return null

        val opCode = command[0].toUByte().toInt()
        val testCd = testCdProvider()

        return when (opCode) {
            0x12 -> handleInquiry(testCd) // INQUIRY
            0x5A -> handleModeSense(expectedResponseLength) // MODE SENSE (10)
            0x43 -> handleReadToc(testCd) // READ TOC
            0xBE -> handleReadCd(command, testCd) // READ CD
            else -> null
        }
    }

    private fun handleInquiry(testCd: TestCd): ByteArray {
        val response = ByteArray(36)
        response[0] = 0x05 // CD-ROM
        // Vendor (8 bytes, offset 8)
        testCd.vendor.padEnd(8).take(8).toByteArray().copyInto(response, 8)
        // Product (16 bytes, offset 16)
        testCd.product.padEnd(16).take(16).toByteArray().copyInto(response, 16)
        // Revision (4 bytes, offset 32)
        testCd.revision.padEnd(4).take(4).toByteArray().copyInto(response, 32)
        return response
    }

    private fun handleModeSense(length: Int): ByteArray {
        val response = ByteArray(length)
        // Capabilities page 0x2A
        // In real drives, this is more complex. Let's just mock C2 support bit.
        if (length > 10) {
            response[10] = 0x01 // Set C2 error pointer support bit
        }
        return response
    }

    private fun handleReadToc(testCd: TestCd): ByteArray {
        val response = ByteArray(4 + (testCd.tracks.size + 1) * 8)
        response[2] = 1 // First track
        response[3] = testCd.tracks.size.toByte() // Last track

        // Track descriptors... for simplicity, let's just make sure the header is correct
        // as the current RippingEngine only uses the header.
        return response
    }

    private fun handleReadCd(command: ByteArray, testCd: TestCd): ByteArray? {
        val lba = ((command[2].toLong() and 0xFF) shl 24) or
                  ((command[3].toLong() and 0xFF) shl 16) or
                  ((command[4].toLong() and 0xFF) shl 8) or
                  (command[5].toLong() and 0xFF)

        val includeC2 = (command[9] and 0x02.toByte()) != 0.toByte()
        val length = if (includeC2) 2352 + 294 else 2352

        // Return dummy audio data (a simple sine wave or pattern)
        val response = ByteArray(length)
        for (i in 0 until 2352) {
            response[i] = (lba xor i.toLong()).toByte()
        }
        return response
    }
}

object TestCdData {
    val CDs = listOf(
        TestCd("Pink Floyd", "The Dark Side of the Moon", "1973", listOf(TestTrack(0, 2400))),
        TestCd("Michael Jackson", "Thriller", "1982", listOf(TestTrack(0, 3000))),
        TestCd("Fleetwood Mac", "Rumours", "1977", listOf(TestTrack(0, 2500))),
        TestCd("Nirvana", "Nevermind", "1991", listOf(TestTrack(0, 2800))),
        TestCd("Daft Punk", "Random Access Memories", "2013", listOf(TestTrack(0, 4000)))
    )
}
