package com.bitperfect.plugin.wiim

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.bitperfect.core.output.TrackInfo
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.PlaybackHandoffState
import com.bitperfect.core.output.PlayerProvider


@UnstableApi
internal class WiimPlayerProvider(
    context: Context,
    device: OutputDevice.Upnp,
    handoffState: PlaybackHandoffState,
) : PlayerProvider {

    private val castPlayer = WiimCastPlayer(context, device)

    override val player = castPlayer

    init {
        val mediaItems = handoffState.tracks.map { track ->
            val extras = Bundle().apply {
                putLong("track_duration_ms", track.durationMs)
                track.filePath?.let { putString("track_file_path", it) }
                track.dataPath?.let { putString("track_data_path", it) }
            }
            val artUri = if (track.albumId != -1L) {
                ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"),
                    track.albumId
                )
            } else null

            MediaItem.Builder()
                .setMediaId("${track.id}")
                .setUri(
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id
                    )
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.albumTitle)
                        .setTrackNumber(track.trackNumber)
                        .setArtworkUri(artUri)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }
        castPlayer.setMediaItems(
            mediaItems,
            handoffState.currentIndex,
            handoffState.positionMs
        )
        if (handoffState.playWhenReady) castPlayer.play()
    }

    override fun release() {
        castPlayer.release()
    }
}
