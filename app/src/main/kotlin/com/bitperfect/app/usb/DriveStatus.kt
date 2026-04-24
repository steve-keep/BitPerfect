package com.bitperfect.app.usb

sealed class DriveStatus(open val info: DriveInfo?) {
    object NoDrive : DriveStatus(null)
    data class Connecting(override val info: DriveInfo? = null) : DriveStatus(info)
    object PermissionDenied : DriveStatus(null)
    object NotOptical : DriveStatus(null)
    data class Empty(override val info: DriveInfo) : DriveStatus(info)
    data class DiscReady(override val info: DriveInfo, val toc: com.bitperfect.core.models.DiscToc) : DriveStatus(info)
    data class Error(val message: String, override val info: DriveInfo? = null) : DriveStatus(info)
}
