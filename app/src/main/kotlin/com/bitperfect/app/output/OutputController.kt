package com.bitperfect.app.output

import com.bitperfect.core.output.OutputDevice

import com.bitperfect.app.library.TrackInfo

/**
 * Contract for any playback output target.
 * Implementations: LocalOutputController, BluetoothOutputController, UpnpOutputController (future).
 *
 * All suspend functions run on whatever dispatcher the caller provides.
 * Implementations must not block the calling coroutine unnecessarily.
 */
interface OutputController {
    /** Current playback position in milliseconds, or 0 if unavailable. */
    suspend fun getPositionMs(): Long

    suspend fun play()
    suspend fun pause()
    suspend fun togglePlayPause()
    suspend fun seekTo(positionMs: Long)

    suspend fun setVolume(volume: Int) { /* no-op for local/BT */ }

    /**
     * Hand off a new queue to this controller and start playback.
     * Called by OutputRepository when switching to this device.
     *
     * @param tracks  Ordered list of tracks matching PlayerRepository's queue format.
     * @param startIndex  Index within tracks to start playing.
     * @param startPositionMs  Position within the track to seek to before playing.
     */
    suspend fun takeOver(tracks: List<TrackInfo>, startIndex: Int, startPositionMs: Long)

    /**
     * Release resources (e.g. disconnect BT profile proxy, stop HTTP server).
     * Called by OutputRepository when switching away from this controller.
     */
    suspend fun release()

    suspend fun skipNext() { /* no-op for local/BT */ }
    suspend fun skipPrev() { /* no-op for local/BT */ }

    /**
     * Appends a track to the current queue.
     * Only implemented for UPnP devices; local/BT use ExoPlayer directly.
     */
    suspend fun appendToQueue(track: TrackInfo) { /* no-op for local/BT */ }

    suspend fun appendAlbumToQueue(tracks: List<TrackInfo>) { /* no-op for local/BT */ }

    suspend fun insertNextInQueue(track: TrackInfo) { /* no-op for local/BT */ }
    suspend fun insertAlbumNextInQueue(tracks: List<TrackInfo>) { /* no-op for local/BT */ }

    suspend fun reorderQueue(fromIndex: Int, toIndex: Int) { /* no-op for local/BT */ }
    suspend fun removeFromQueue(index: Int) { /* no-op for local/BT */ }
}
