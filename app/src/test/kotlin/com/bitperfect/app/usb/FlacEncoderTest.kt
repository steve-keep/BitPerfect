package com.bitperfect.app.usb

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE)
class FlacEncoderTest {

    @Before
    fun setup() {
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_FLAC, ShadowMediaCodec.CodecConfig(
            16384,
            32768,
            ShadowMediaCodec.CodecConfig.Codec { inBuf, outBuf ->
                // The MediaCodec drain loop checks outputBuffer.limit() and size.
                // We mock an encoder that writes the fLaC header and then copies input.
                val fLaC = byteArrayOf(0x66, 0x4C, 0x61, 0x43)
                outBuf.put(fLaC)
                val remaining = inBuf.remaining()
                val chunk = ByteArray(remaining)
                inBuf.get(chunk)
                outBuf.put(chunk)
            }
        ))
    }

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

    @Test
    fun encode_eosWithEmptyPcmAndNullCodec_returnsWithoutHanging() {
        val outputStream = ByteArrayOutputStream()
        val encoder = FlacEncoder(outputStream)

        // Set isConfigured to true via reflection to bypass start() which calls Android Media APIs
        // that throw in pure unit tests. This leaves mediaCodec as null.
        val field = FlacEncoder::class.java.getDeclaredField("isConfigured")
        field.isAccessible = true
        field.set(encoder, true)

        // This should return immediately without throwing or hanging
        encoder.encode(ByteArray(0), isEndOfStream = true)

        // Note: Testing that eosSubmitted logic exits the loop after EOS is queued
        // when MediaCodec is present requires an integration test with a real codec,
        // because MediaCodec APIs are largely native and hard to mock thoroughly
        // for this exact timing scenario.
    }

    @Test
    fun stop_doesNotWriteAfterEos() {
        val pcmData = ByteArray(16384) // 4096 samples of silence

        val outputStream1 = ByteArrayOutputStream()
        val encoder1 = FlacEncoder(outputStream1)
        encoder1.encode(pcmData)
        encoder1.stop()
        val out1 = outputStream1.toByteArray()

        val outputStream2 = ByteArrayOutputStream()
        val encoder2 = FlacEncoder(outputStream2)
        encoder2.encode(pcmData)
        encoder2.stop()
        val out2 = outputStream2.toByteArray()

        assertArrayEquals(out1, out2)
    }

    @Test
    fun stop_completesFast() {
        val pcmData = ByteArray(18816) // 8 sectors worth of silence
        val outputStream = ByteArrayOutputStream()
        val encoder = FlacEncoder(outputStream)

        encoder.encode(pcmData)

        val startTime = System.currentTimeMillis()
        encoder.stop()
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("stop() took too long: ${elapsed}ms", elapsed < 2000)
    }

    @Test
    fun writeHeader_false_producesNoFlacMarker() {
        val pcmData = ByteArray(16384)
        val outputStream = ByteArrayOutputStream()
        val encoder = FlacEncoder(outputStream, writeHeader = false)

        encoder.encode(pcmData)
        encoder.stop()

        val output = outputStream.toByteArray()
        val flacMarker = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // fLaC

        val containsMarker = indexOfSubArray(output, flacMarker) != -1
        assertFalse("Output should not contain fLaC marker", containsMarker)
    }

    @Test
    fun writeHeader_true_producesValidFlacMarker() {
        val pcmData = ByteArray(16384)
        val outputStream = ByteArrayOutputStream()
        val encoder = FlacEncoder(outputStream, writeHeader = true)

        encoder.encode(pcmData)
        encoder.stop()

        val output = outputStream.toByteArray()
        assertTrue("Output is too short", output.size >= 4)
        assertEquals(0x66.toByte(), output[0])
        assertEquals(0x4C.toByte(), output[1])
        assertEquals(0x61.toByte(), output[2])
        assertEquals(0x43.toByte(), output[3])
    }

    private fun indexOfSubArray(array: ByteArray, subArray: ByteArray): Int {
        if (subArray.isEmpty() || array.size < subArray.size) return -1
        for (i in 0..array.size - subArray.size) {
            var match = true
            for (j in subArray.indices) {
                if (array[i + j] != subArray[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
