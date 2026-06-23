package com.bitperfect.plugin.wiim

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.bitperfect.core.output.OutputDevice
import com.bitperfect.core.output.PlaybackHandoffState
import com.bitperfect.core.output.PlayerProvider
import com.bitperfect.core.output.TrackInfo

@UnstableApi
class WiimPlayerProvider(
    context: Context,
    device: OutputDevice.Upnp,
    private val handoffState: PlaybackHandoffState,
) : PlayerProvider {

    private val castPlayer = WiimCastPlayer(
        context         = context,
        targetDevice    = device,
        initialPlaylist = handoffState.tracks.map { it.toMediaItem() },
        initialIndex    = handoffState.currentIndex,
    )

    override val player = castPlayer

    /**
     * Must be called by [PlaybackService] AFTER setting [player] on the
     * [MediaLibrarySession]. Media3 resets the session player's queue when
     * [session.player] is assigned, so we must populate the queue afterwards
     * to avoid being overwritten.
     */
    fun activate() {
        val mediaItems = handoffState.tracks.map { it.toMediaItem() }
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

private fun TrackInfo.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putLong("track_duration_ms", durationMs)
        filePath?.let { putString("track_file_path", it) }
        dataPath?.let { putString("track_data_path", it) }
    }
    val artUri = if (albumId != -1L) {
        ContentUris.withAppendedId(
            android.net.Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
    } else null

    return MediaItem.Builder()
        .setMediaId("$id")
        .setUri(
            ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
            )
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(albumTitle)
                .setTrackNumber(trackNumber)
                .setArtworkUri(artUri)
                .setExtras(extras)
                .build()
        )
        .build()
}
