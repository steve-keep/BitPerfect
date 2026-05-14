package com.bitperfect.app.output

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
    suspend fun seekTo(positionMs: Long)

    /**
     * Hand off a new queue to this controller and start playback.
     * Called by OutputRepository when switching to this device.
     *
     * @param mediaIds  Ordered list of media IDs matching PlayerRepository's queue format.
     * @param startIndex  Index within mediaIds to start playing.
     * @param startPositionMs  Position within the track to seek to before playing.
     */
    suspend fun takeOver(mediaIds: List<String>, startIndex: Int, startPositionMs: Long)

    /**
     * Release resources (e.g. disconnect BT profile proxy, stop HTTP server).
     * Called by OutputRepository when switching away from this controller.
     */
    suspend fun release()
}
