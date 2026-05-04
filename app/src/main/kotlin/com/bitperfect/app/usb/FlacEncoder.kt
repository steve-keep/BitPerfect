package com.bitperfect.app.usb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.OutputStream
import java.nio.ByteBuffer

class FlacEncoder(
    private val outputStream: OutputStream,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2
) {
    private var mediaCodec: MediaCodec? = null
    private var isConfigured = false

    fun start() {
        // Write FLAC header
        outputStream.write("fLaC".toByteArray())

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec?.start()
        isConfigured = true
    }

    fun encode(pcmData: ByteArray, isEndOfStream: Boolean = false) {
        if (!isConfigured) start()

        val codec = mediaCodec ?: return
        var offset = 0
        while (offset < pcmData.size || isEndOfStream) {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()

                val length = minOf(pcmData.size - offset, inputBuffer?.capacity() ?: 0)
                if (length > 0) {
                    inputBuffer?.put(pcmData, offset, length)
                }

                val flags = if (isEndOfStream && offset + length >= pcmData.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inputBufferIndex, 0, length, 0, flags)

                offset += length
                if (isEndOfStream && offset >= pcmData.size) break
            }

            drainEncoder(codec)
        }
    }

    private fun drainEncoder(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Ignore
            } else if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    // For FLAC, MediaCodec often puts CSD (STREAMINFO) in the first buffer or buffers with BUFFER_FLAG_CODEC_CONFIG.
                    // Actually, Android MediaCodec outputs raw FLAC frames. The FLAC header "fLaC" and STREAMINFO block
                    // are required. Some devices output just raw frames, while others output the "fLaC" and STREAMINFO in the config block.
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)

                    // Write out
                    outputStream.write(chunk)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun stop() {
        val codec = mediaCodec ?: return
        encode(ByteArray(0), isEndOfStream = true)

        drainEncoder(codec)

        codec.stop()
        codec.release()
        mediaCodec = null
        isConfigured = false
    }
}
