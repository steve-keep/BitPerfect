package com.bitperfect.app.usb

import android.content.Context
import android.hardware.usb.UsbEndpoint
import kotlinx.coroutines.flow.StateFlow

object DeviceStateManager {
    private var usbDriveDetector: UsbDriveDetector? = null

    lateinit var driveStatus: StateFlow<DriveStatus>
        private set

    fun initialize(context: Context) {
        if (usbDriveDetector == null) {
            val detector = UsbDriveDetector(context.applicationContext)
            usbDriveDetector = detector
            driveStatus = detector.driveStatus
        }
    }

    fun rescan() {
        usbDriveDetector?.scanForDevices()
    }

    fun reportError(message: String) {
        usbDriveDetector?.reportError(message)
    }

    fun pausePolling() {
        usbDriveDetector?.pausePolling()
    }

    fun resumePolling() {
        usbDriveDetector?.resumePolling()
    }

    fun getTransport(): UsbTransport? = usbDriveDetector?.transport
    fun getInEndpoint(): UsbEndpoint? = usbDriveDetector?.inEndpoint
    fun getOutEndpoint(): UsbEndpoint? = usbDriveDetector?.outEndpoint
}
