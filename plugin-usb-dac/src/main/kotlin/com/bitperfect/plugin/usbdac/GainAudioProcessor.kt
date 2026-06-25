package com.bitperfect.plugin.usbdac

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
class GainAudioProcessor : BaseAudioProcessor() {

    // AtomicReference so PlaybackService can write from any thread safely
    private val pendingVolume = AtomicReference(1.0f)
    private var currentVolume = 1.0f

    fun setVolume(volume: Float) {
        pendingVolume.set(volume.coerceIn(0f, 1f))
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only handle 16-bit PCM — CD audio is always 16-bit
        if (inputAudioFormat.encoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        currentVolume = pendingVolume.get()
        if (currentVolume == 1.0f) {
            // Unity gain — pass through without copying
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
            return
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.hasRemaining()) {
            // 16-bit little-endian samples
            val sample = inputBuffer.short
            val scaled = (sample * currentVolume).toInt().coerceIn(-32768, 32767).toShort()
            output.putShort(scaled)
        }
        output.flip()
    }
}
