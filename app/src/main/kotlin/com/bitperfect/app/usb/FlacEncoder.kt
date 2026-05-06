package com.bitperfect.app.usb

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.bitperfect.core.utils.AppLogger
import java.io.OutputStream
import java.nio.ByteBuffer

internal fun isFLACHeader(buffer: ByteArray, offset: Int, size: Int): Boolean =
    size >= 4 &&
    buffer[offset] == 0x66.toByte() &&   // 'f'
    buffer[offset + 1] == 0x4C.toByte() && // 'L'
    buffer[offset + 2] == 0x61.toByte() && // 'a'
    buffer[offset + 3] == 0x43.toByte()    // 'C'

class FlacEncoder(
    private val outputStream: OutputStream,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 2
) {
    private var mediaCodec: MediaCodec? = null
    private var isConfigured = false
    internal var hasWrittenHeader = false
    private var presentationTimeUs = 0L

    fun start() {
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
        var eosSubmitted = false

        while (offset < pcmData.size || (isEndOfStream && !eosSubmitted)) {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()

                val remaining = pcmData.size - offset
                val length = minOf(remaining, inputBuffer?.capacity() ?: 0)
                if (length > 0) {
                    inputBuffer?.put(pcmData, offset, length)
                }
                offset += length

                val atEnd = isEndOfStream && offset >= pcmData.size
                val flags = if (atEnd) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0

                codec.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, flags)

                val samplesInBuffer = length / (channelCount * 2) // 16-bit PCM: 2 bytes per sample per channel
                presentationTimeUs += (samplesInBuffer * 1_000_000L) / sampleRate

                if (atEnd) eosSubmitted = true
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
                    // BUFFER_FLAG_CODEC_CONFIG carries the FLAC STREAMINFO block on most devices.
                    // We write it as normal output — do not skip it. The manual "fLaC" header write
                    // has been removed from start() so this is the sole source of the container header.
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)

                    processAndWriteChunk(chunk)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    internal fun processAndWriteChunk(chunk: ByteArray) {
        if (isFLACHeader(chunk, 0, chunk.size)) {
            if (!hasWrittenHeader) {
                outputStream.write(chunk)
                hasWrittenHeader = true
            } else {
                AppLogger.w("FlacEncoder", "Duplicate FLAC header detected, skipping")
            }
        } else {
            outputStream.write(chunk)
        }
    }

    fun stop() {
        val codec = mediaCodec ?: return
        encode(ByteArray(0), isEndOfStream = true)

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue
            } else if (outputBufferIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuffer.get(chunk)

                    processAndWriteChunk(chunk)
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }

        codec.stop()
        codec.release()
        mediaCodec = null
        isConfigured = false
        hasWrittenHeader = false
        presentationTimeUs = 0L
    }
}
