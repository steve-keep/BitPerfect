package com.bitperfect.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyserTest {

    private fun generateSineWave(freq: Float, durationSeconds: Float, amplitude: Float = 0.5f): ByteArray {
        val totalSamples = (durationSeconds * 44100).toInt()
        val chunk = ByteArray(totalSamples * 4)
        for (i in 0 until totalSamples) {
            val t = i.toFloat() / 44100f
            val sampleVal = sin(2f * PI.toFloat() * freq * t) * amplitude
            val shortVal = (sampleVal * 32767f).toInt().toShort()

            val idx = i * 4
            // Left channel
            chunk[idx] = (shortVal.toInt() and 0xFF).toByte()
            chunk[idx + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
            // Right channel
            chunk[idx + 2] = chunk[idx]
            chunk[idx + 3] = chunk[idx + 1]
        }
        return chunk
    }

    private fun generateImpulseTrain(bpm: Float, durationSeconds: Float, amplitude: Float = 1.0f): ByteArray {
        val totalSamples = (durationSeconds * 44100).toInt()
        val chunk = ByteArray(totalSamples * 4)
        val samplesPerBeat = (44100f * 60f / bpm).toInt()

        for (i in 0 until totalSamples) {
            val sampleVal = if (i % samplesPerBeat == 0) amplitude else 0f
            val shortVal = (sampleVal * 32767f).toInt().toShort()

            val idx = i * 4
            chunk[idx] = (shortVal.toInt() and 0xFF).toByte()
            chunk[idx + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
            chunk[idx + 2] = chunk[idx]
            chunk[idx + 3] = chunk[idx + 1]
        }
        return chunk
    }

    @Test
    fun testSilence() {
        val analyser = AudioAnalyser()
        val silence = ByteArray(44100 * 4 * 2) // 2 seconds of silence
        analyser.feed(silence)
        val result = analyser.analyse()

        assertEquals(0f, result.bpm, 0.01f)
        assertEquals("", result.initialKey)
        assertEquals(0f, result.replayGainPeak, 0.01f)
        assertEquals(0f, result.energy, 0.01f)
    }

    @Test
    fun testSineWaveKeyDetection() {
        val analyser = AudioAnalyser()
        // 440 Hz is A4. Should detect A Major or A Minor.
        // A Minor is 8A. A Major is 11B. Let's just check if it finds a valid key.
        val sineWave = generateSineWave(440f, 3.0f, 0.8f)
        analyser.feed(sineWave)
        val result = analyser.analyse()

        assertTrue("Key should be detected", result.initialKey.isNotEmpty())
        // For a pure 440Hz sine wave, A is the only frequency, so A major/minor will match.
        assertTrue("Key should be A major (11B) or A minor (8A)", result.initialKey == "11B" || result.initialKey == "8A")

        // Energy should be > 0
        assertTrue("Energy should be greater than 0", result.energy > 0.5f)
        assertTrue("Peak should be close to 0.8", result.replayGainPeak in 0.7f..0.9f)
    }

    @Test
    fun testImpulseTrainBpmDetection() {
        val analyser = AudioAnalyser()
        // 120 BPM impulse train for 5 seconds
        val impulses = generateImpulseTrain(120f, 5.0f, 1.0f)
        analyser.feed(impulses)
        val result = analyser.analyse()

        // BPM should be roughly 120
        assertTrue("BPM should be near 120 (got ${result.bpm})", result.bpm in 115f..125f)
    }
}
