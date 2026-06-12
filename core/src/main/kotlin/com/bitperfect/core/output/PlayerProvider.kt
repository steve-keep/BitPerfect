package com.bitperfect.core.output

import androidx.media3.common.Player

/**
 * Encapsulates the [Player] instance (ExoPlayer or SimpleBasePlayer) that
 * a plugin has configured for its output target.
 *
 * [PlaybackService] sets [player] on the [MediaLibrarySession] and holds
 * the [PlayerProvider] reference until the device switches away, at which
 * point it calls [release].
 *
 * Implementations must be thread-safe: [release] may be called from any
 * thread.
 */
@androidx.media3.common.util.UnstableApi
interface PlayerProvider {

    /**
     * The configured player. Set on [MediaLibrarySession] immediately
     * after [OutputPlugin.createPlayerProvider] returns.
     *
     * Must not be null. Must not be released before [release] is called.
     */
    val player: Player

    /**
     * Release resources held by this provider.
     *
     * Called by [PlaybackService] when switching away from this device.
     * After this call [player] must not be used.
     *
     * Implementations should:
     *  - Stop playback
     *  - Release the underlying Player ([ExoPlayer.release] or equivalent)
     *  - Stop any background services (HTTP servers, poll loops, etc.)
     *  - Cancel all coroutines owned by this provider
     */
    fun release()
}
