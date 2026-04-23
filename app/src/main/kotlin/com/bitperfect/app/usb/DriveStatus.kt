package com.bitperfect.app.usb

sealed class DriveStatus {
    /** No USB mass storage device is connected. */
    object NoDrive : DriveStatus()

    /** A USB device has been found; USB permission dialog shown or SCSI INQUIRY in flight. */
    object Connecting : DriveStatus()

    /** User tapped "Deny" on the USB permission dialog. */
    object PermissionDenied : DriveStatus()

    /**
     * A USB mass storage device is connected and responded to INQUIRY,
     * but Peripheral Device Type ≠ 0x05 — it is not an optical drive.
     */
    object NotOptical : DriveStatus()

    /**
     * The drive is an optical drive but TEST UNIT READY returned Not Ready —
     * no disc is currently loaded.
     */
    object Empty : DriveStatus()

    /** A disc is loaded and the drive is ready to rip. */
    data class DiscReady(val info: DriveInfo) : DriveStatus()

    /** USB communication failed, interface claim failed, or a SCSI command returned an error. */
    data class Error(val message: String) : DriveStatus()
}
