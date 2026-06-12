package com.bitperfect.core.output

import android.content.Context

/**
 * Contract for a self-contained output plugin.
 *
 * A plugin:
 *  1. Discovers its device type (USB, UPnP, etc.) via [attach].
 *  2. Provides a configured [PlayerProvider] when its device is selected.
 *  3. Cleans up on [release].
 *
 * Plugins are registered in [OutputPluginRegistry] at app startup and
 * are not imported by [PlaybackService] or [OutputRepository] directly —
 * those classes interact only with [OutputPluginRegistry].
 */
@androidx.media3.common.util.UnstableApi
interface OutputPlugin {

    /**
     * Unique string tag identifying the [OutputDevice] subtype this plugin
     * handles. Must be stable across process restarts (used for preferences).
     *
     * Examples: "usb_dac", "upnp_wiim", "bluetooth"
     */
    val deviceType: String

    /**
     * Called once at app start. The plugin should:
     *  - Start device discovery (SSDP, USB broadcast receiver, etc.)
     *  - Register itself with [registry] so discovered [OutputDevice]s
     *    are included in [OutputPluginRegistry.availableDevices]
     *
     * Must not block. Discovery runs asynchronously; devices are emitted
     * into the registry as they are found.
     */
    fun attach(registry: OutputPluginRegistry)

    /**
     * Called by [PlaybackService] when this plugin's device type is
     * selected as the active output.
     *
     * The plugin must return a [PlayerProvider] whose [PlayerProvider.player]
     * is ready to be set on [MediaLibrarySession]. The plugin is responsible
     * for restoring playback from [handoffState].
     *
     * This call may block briefly (e.g. to start a local HTTP server) but
     * must not do network I/O on the calling thread. Long-running setup
     * (handshake with the remote device) should be initiated here and
     * completed asynchronously.
     *
     * @param context  Application context.
     * @param device   The specific device being activated (guaranteed to
     *                 match this plugin's [deviceType]).
     * @param handoffState  Position and queue from the previously active player.
     */
    fun createPlayerProvider(
        context: Context,
        device: OutputDevice,
        handoffState: PlaybackHandoffState,
    ): PlayerProvider

    /**
     * Called when the plugin is permanently torn down (app process ending,
     * or plugin being unregistered). Stop all discovery, cancel all
     * coroutines, release all resources.
     *
     * Distinct from [PlayerProvider.release], which is per-session.
     * [release] is called at most once per [OutputPlugin] lifetime.
     */
    fun release()
}
