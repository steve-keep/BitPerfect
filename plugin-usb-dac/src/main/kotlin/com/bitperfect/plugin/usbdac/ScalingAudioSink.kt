package com.bitperfect.plugin.usbdac

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.AudioSink
import com.bitperfect.core.UsbDacDebugLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class ScalingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    // Store as scaled integer (0–10000) to avoid floating point atomics
    private val volumeScaled = AtomicInteger(10000)

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val gain = if (clamped == 0f) 0f else Math.pow(10.0, ((clamped - 1.0) * 3.0)).toFloat()
        volumeScaled.set((gain * 10000).toInt())
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val scale = volumeScaled.get()
        if (scale == 10000) {
            // Unity gain — pass through unmodified, preserving bit-perfect
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // Scale 16-bit PCM samples in-place on a copy
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val scaled = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            val sample = input.short
            val out = (sample * scale / 10000).coerceIn(-32768, 32767).toShort()
            scaled.putShort(out)
        }
        scaled.flip()

        return super.handleBuffer(scaled, presentationTimeUs, encodedAccessUnitCount)
    }
}
