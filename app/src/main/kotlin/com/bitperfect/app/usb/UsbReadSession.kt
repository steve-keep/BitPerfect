package com.bitperfect.app.usb

import com.bitperfect.core.utils.AppLogger
import java.io.Closeable

/**
 * Owns the USB transport lifecycle for the duration of a raw sector read operation.
 *
 * Pauses the background polling loop on [open] (preventing BOT transaction corruption)
 * and resumes it on [close]. Callers must use [open] via a try-with-resources block
 * (`use {}`) to guarantee [close] is always called, even on error or cancellation.
 *
 * Usage:
 * ```kotlin
 * UsbReadSession.open().use { session ->
 *     val data = session.readSectors(lba = 1, sectorCount = 8)
 * }
 * ```
 */
class UsbReadSession private constructor(
    private val readCmd: ReadCdCommand
) : Closeable {

    /**
     * Reads [sectorCount] sectors starting at native LBA [lba].
     *
     * Retries up to [MAX_RETRIES] times. Returns `null` if all retries fail or the
     * drive becomes unready during a retry.
     */
    fun readSectors(lba: Int, sectorCount: Int): ByteArray? = readWithRetry(
        execute = { l, n -> readCmd.execute(l, n) },
        isDiscReady = { DeviceStateManager.driveStatus.value is DriveStatus.DiscReady },
        lba = lba,
        sectorCount = sectorCount
    )

    /** Resumes the background polling loop. Always called, even on exception. */
    override fun close() {
        DeviceStateManager.resumePolling()
        AppLogger.d(TAG, "Session closed, polling resumed")
    }

    companion object {
        private const val TAG = "UsbReadSession"
        internal const val MAX_RETRIES = 3

        /**
         * Acquires the USB transport from [DeviceStateManager], pauses the polling loop,
         * and returns a [UsbReadSession] ready for use.
         *
         * @throws IllegalStateException if the transport or endpoints are not available.
         */
        fun open(): UsbReadSession {
            val transport = DeviceStateManager.getTransport()
                ?: throw IllegalStateException("USB transport not available")
            val outEndpoint = DeviceStateManager.getOutEndpoint()
                ?: throw IllegalStateException("USB out-endpoint not available")
            val inEndpoint = DeviceStateManager.getInEndpoint()
                ?: throw IllegalStateException("USB in-endpoint not available")

            DeviceStateManager.pausePolling()
            AppLogger.d(TAG, "Session opened, polling paused")
            return UsbReadSession(ReadCdCommand(transport, outEndpoint, inEndpoint))
        }
    }
}

internal fun readWithRetry(
    execute: (lba: Int, count: Int) -> ByteArray?,
    isDiscReady: () -> Boolean,
    lba: Int,
    sectorCount: Int,
    maxRetries: Int = UsbReadSession.MAX_RETRIES
): ByteArray? {
    for (attempt in 1..maxRetries) {
        val data = execute(lba, sectorCount)
        if (data != null) return data
        if (!isDiscReady()) return null
    }
    return null
}
