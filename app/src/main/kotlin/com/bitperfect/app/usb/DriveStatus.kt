package com.bitperfect.app.usb

sealed class DriveStatus {
    object NoDrive : DriveStatus()
    object Connecting : DriveStatus()
    object PermissionDenied : DriveStatus()
    object NotOptical : DriveStatus()
    object Empty : DriveStatus()
    data class DiscReady(val info: DriveInfo) : DriveStatus()
    data class Error(val message: String) : DriveStatus()
}
