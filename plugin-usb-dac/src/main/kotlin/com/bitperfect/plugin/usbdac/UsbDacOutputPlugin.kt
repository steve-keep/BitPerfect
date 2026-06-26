package com.bitperfect.plugin.usbdac

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.bitperfect.core.output.TrackInfo
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.OutputPlugin
import com.bitperfect.core.output.OutputPluginRegistry
import com.bitperfect.core.output.PlaybackHandoffState
import com.bitperfect.core.output.PlayerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class UsbDacOutputPlugin(
    private val appContext: Context,
    private val detectorFactory: (Context) -> UsbAudioDacDetector = { ctx -> UsbAudioDacDetector(ctx) }
) : OutputPlugin {

    override val deviceType: String = "usb_dac"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var detector: UsbAudioDacDetector? = null

    override fun attach(registry: OutputPluginRegistry) {
        val det = detectorFactory(appContext)
        detector = det

        scope.launch {
            det.state.collect { dacState ->
                when (dacState) {
                    is UsbDacState.Connected -> {
                        val device = OutputDevice.UsbDac(
                            device = dacState.device,
                            protocol = dacState.protocol,
                            productName = dacState.productName,
                        )
                        registry.updateDevices(deviceType, listOf(device))
                    }
                    is UsbDacState.Absent,
                    is UsbDacState.PermissionPending -> {
                        registry.updateDevices(deviceType, emptyList())
                    }
                }
            }
        }
    }

    override fun createPlayerProvider(
        context: Context,
        device: OutputDevice,
        handoffState: PlaybackHandoffState,
    ): PlayerProvider {
        val mediaItems = handoffState.tracks.map { it.toMediaItem(context) }
        val renderersFactory = UsbAudioRenderersFactory(context)
        val exoPlayer = buildExoPlayer(context, renderersFactory)
        return ExoPlayerProvider(
            exoPlayer       = exoPlayer,
            renderersFactory = renderersFactory,
            mediaItems      = mediaItems,
            startIndex      = handoffState.currentIndex,
            startPositionMs = handoffState.positionMs,
            playWhenReady   = handoffState.playWhenReady,
        )
    }

    override fun release() {
        detector?.destroy()
        detector = null
        scope.cancel()
    }

    private fun buildExoPlayer(
        context: Context,
        renderersFactory: UsbAudioRenderersFactory,
    ): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }
}

/**
 * Converts a [TrackInfo] into a Media3 [MediaItem] with a MediaStore URI.
 */
private fun TrackInfo.toMediaItem(context: Context): MediaItem {
    val uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
    )
    val artUri = if (albumId != -1L) {
        ContentUris.withAppendedId(
            android.net.Uri.parse("content://media/external/audio/albumart"), albumId
        )
    } else null

    return MediaItem.Builder()
        .setMediaId("$id")
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setTrackNumber(trackNumber)
                .setArtworkUri(artUri)
                .build()
        )
        .build()
}
