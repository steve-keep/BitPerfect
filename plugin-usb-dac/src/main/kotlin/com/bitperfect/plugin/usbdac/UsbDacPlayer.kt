package com.bitperfect.plugin.usbdac

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bitperfect.core.UsbDacDebugLogger
import kotlinx.coroutines.flow.MutableStateFlow

@UnstableApi
class UsbDacPlayer(
    private val exoPlayer: ExoPlayer,
    private val usbDacVolumeFlow: MutableStateFlow<Float>,
) : androidx.media3.common.ForwardingPlayer(exoPlayer) {

    override fun getDeviceVolume(): Int = (usbDacVolumeFlow.value * 100).toInt()
    override fun isDeviceMuted(): Boolean = usbDacVolumeFlow.value == 0f
    override fun getDeviceInfo() = androidx.media3.common.DeviceInfo(
        androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_LOCAL, 0, 100
    )

    override fun getAvailableCommands(): Player.Commands =
        exoPlayer.availableCommands.buildUpon()
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()

    override fun setDeviceVolume(volume: Int, flags: Int) {
        val normalized = volume.coerceIn(0, 100) / 100f
        UsbDacDebugLogger.log("UsbDacPlayer.setDeviceVolume: $volume -> $normalized")
        usbDacVolumeFlow.value = normalized
    }

    override fun increaseDeviceVolume(flags: Int) {
        val next = (usbDacVolumeFlow.value + 0.10f).coerceIn(0f, 1f)
        UsbDacDebugLogger.log("UsbDacPlayer.increaseDeviceVolume -> $next")
        usbDacVolumeFlow.value = next
    }

    override fun decreaseDeviceVolume(flags: Int) {
        val next = (usbDacVolumeFlow.value - 0.10f).coerceIn(0f, 1f)
        UsbDacDebugLogger.log("UsbDacPlayer.decreaseDeviceVolume -> $next")
        usbDacVolumeFlow.value = next
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        usbDacVolumeFlow.value = if (muted) 0f else 0.10f
    }
}
