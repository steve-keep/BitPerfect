package com.bitperfect.plugin.usbdac

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.decent.usbaudio.media3.UsbAudioSink
import com.decent.usbaudio.media3.UsbAudioSinkConfig

@UnstableApi
class UsbAudioRenderersFactory(
    context: Context
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val defaultSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()

        val config = UsbAudioSinkConfig(
            bitPerfectEnabled = true,
            forceRouteToSpeaker = false,
        )

        return UsbAudioSink(defaultSink, context, config)
    }
}