package com.bitperfect.driver

interface IScsiDriver {
    fun getDriverVersion(): String
    fun executeScsiCommand(
        fd: Int,
        command: ByteArray,
        expectedResponseLength: Int,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01,
        timeout: Int = 5000
    ): ByteArray?
}
