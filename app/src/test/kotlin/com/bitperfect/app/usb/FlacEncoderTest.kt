package com.bitperfect.app.usb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class FlacEncoderTest {

    @Test
    fun isFLACHeader_withValidHeader_returnsTrue() {
        val buffer = byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x00, 0x00) // "fLaC" followed by some data
        assertTrue(isFLACHeader(buffer, 0, buffer.size))
    }

    @Test
    fun isFLACHeader_withInvalidHeader_returnsFalse() {
        val buffer = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertFalse(isFLACHeader(buffer, 0, buffer.size))
    }

    @Test
    fun isFLACHeader_withShortBuffer_returnsFalse() {
        val buffer = byteArrayOf(0x66, 0x4C, 0x61) // "fLa"
        assertFalse(isFLACHeader(buffer, 0, buffer.size))
    }

    @Test
    fun isFLACHeader_withDifferentOffset_respectsOffset() {
        val buffer = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x66, 0x4C, 0x61, 0x43)
        // Offset 4 should be true
        assertTrue(isFLACHeader(buffer, 4, buffer.size - 4))
        // Offset 0 should be false
        assertFalse(isFLACHeader(buffer, 0, buffer.size))
    }

    @Test
    fun processAndWriteChunk_withDuplicateHeader_skipsDuplicate() {
        val outputStream = ByteArrayOutputStream()
        val encoder = FlacEncoder(outputStream)

        val headerChunk = byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x01, 0x02, 0x03)
        val dataChunk = byteArrayOf(0x0A, 0x0B, 0x0C)

        // Initial state
        assertFalse(encoder.hasWrittenHeader)

        // Write header first time
        encoder.processAndWriteChunk(headerChunk)
        assertTrue(encoder.hasWrittenHeader)
        assertArrayEquals(headerChunk, outputStream.toByteArray())

        // Write data chunk
        encoder.processAndWriteChunk(dataChunk)
        val expectedAfterData = headerChunk + dataChunk
        assertArrayEquals(expectedAfterData, outputStream.toByteArray())

        // Write duplicate header chunk
        encoder.processAndWriteChunk(headerChunk)
        assertTrue(encoder.hasWrittenHeader) // Should remain true
        // Output stream should NOT have changed (duplicate skipped)
        assertArrayEquals(expectedAfterData, outputStream.toByteArray())
    }
}
