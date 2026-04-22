package com.bitperfect.driver

class ScsiDriver : IScsiDriver {
    override external fun getDriverVersion(): String

    override external fun initDevice(
        fd: Int,
        interfaceNumber: Int,
        endpointIn: Int,
        endpointOut: Int
    ): Boolean

    /**
     * Executes a raw SCSI command.
     * @param fd The file descriptor of the USB device (from UsbDeviceConnection).
     * @param command The raw SCSI command bytes (CDB).
     * @param expectedResponseLength The expected length of the response.
     * @param endpointIn The input endpoint address.
     * @param endpointOut The output endpoint address.
     * @param timeout The timeout in milliseconds.
     * @return The response bytes from the device.
     */
    override external fun executeScsiCommand(
        fd: Int,
        command: ByteArray,
        expectedResponseLength: Int,
        endpointIn: Int,
        endpointOut: Int,
        timeout: Int
    ): ByteArray?

    companion object {
        private var isLibraryLoaded = false
        init {
            try {
                System.loadLibrary("bitperfect-driver")
                isLibraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // Ignore for unit tests
            }
        }
    }
}
