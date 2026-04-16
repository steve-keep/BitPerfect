package com.bitperfect.core.engine

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.OutputStream
import java.nio.ByteBuffer

class FlacEncoder {
    private var encoder: MediaCodec? = null
    private var outputStream: OutputStream? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    fun prepare(outputStream: OutputStream, sampleRate: Int, channels: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoderName = codecList.findEncoderForFormat(format) ?: run {
            android.util.Log.e("FlacEncoder", "No FLAC encoder found")
            return
        }

        try {
            encoder = MediaCodec.createByCodecName(encoderName)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            this.outputStream = outputStream
        } catch (e: Exception) {
            android.util.Log.e("FlacEncoder", "Error initializing encoder: ${e.message}")
            encoder = null
        }
    }

    fun encode(data: ByteArray, isEndOfStream: Boolean = false) {
        val encoder = this.encoder ?: return
        val inputBufferIndex = encoder.dequeueInputBuffer(10000)
        if (inputBufferIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
            inputBuffer?.clear()
            inputBuffer?.put(data)
            val flags = if (isEndOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            encoder.queueInputBuffer(inputBufferIndex, 0, data.size, 0, flags)
        }

        drain()
    }

    private fun drain() {
        val encoder = this.encoder ?: return
        var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
        while (outputBufferIndex >= 0) {
            val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
            val chunk = ByteArray(bufferInfo.size)
            outputBuffer?.get(chunk)
            outputStream?.write(chunk)
            encoder.releaseOutputBuffer(outputBufferIndex, false)

            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                break
            }
            outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun finish() {
        encode(byteArrayOf(), isEndOfStream = true)
        encoder?.stop()
        encoder?.release()
        encoder = null
        outputStream?.close()
        outputStream = null
    }
}
