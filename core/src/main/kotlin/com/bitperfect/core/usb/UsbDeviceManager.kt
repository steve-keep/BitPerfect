package com.bitperfect.core.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbDeviceManager(private val context: Context, private val onLog: ((String) -> Unit)? = null) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun log(message: String) {
        Log.d("UsbDeviceManager", message)
        onLog?.invoke("[UsbDeviceManager] $message")
    }

    fun getCompatibleDevices(): List<UsbDevice> {
        val deviceList = usbManager.deviceList
        val devices = deviceList.values.filter { isCompatibleDevice(it) }
        for (device in devices) {
            log("\nUSB Descriptor Info:\n" + getDeviceInfo(device))
        }
        return devices
    }

    private fun getDeviceInfo(device: UsbDevice): String {
        return buildString {
            appendLine("Manufacturer: ${device.manufacturerName}")
            appendLine("Product: ${device.productName}")
            appendLine("Vendor ID: 0x${device.vendorId.toString(16).uppercase()}")
            appendLine("Product ID: 0x${device.productId.toString(16).uppercase()}")
            appendLine("Device Class: ${device.deviceClass}")
            appendLine("Device Subclass: ${device.deviceSubclass}")
            appendLine("Device Protocol: ${device.deviceProtocol}")
            appendLine("Device Name: ${device.deviceName}")
            appendLine("Device ID: ${device.deviceId}")
            appendLine("Version: ${device.version}")
            try {
                appendLine("Serial Number: ${device.serialNumber}")
            } catch (e: SecurityException) {
                appendLine("Serial Number: [Access Denied]")
            }
            appendLine("Configuration Count: ${device.configurationCount}")

            for (i in 0 until device.configurationCount) {
                val config = device.getConfiguration(i)
                appendLine("  Configuration $i:")
                appendLine("    Id: ${config.id}")
                appendLine("    Name: ${config.name}")
                appendLine("    Max Power: ${config.maxPower}")
                appendLine("    Is Self Powered: ${config.isSelfPowered}")
                appendLine("    Is Remote Wakeup: ${config.isRemoteWakeup}")
                appendLine("    Interface Count: ${config.interfaceCount}")

                for (j in 0 until config.interfaceCount) {
                    val iface = config.getInterface(j)
                    appendLine("    Interface $j:")
                    appendLine("      Id: ${iface.id}")
                    appendLine("      Alternate Setting: ${iface.alternateSetting}")
                    appendLine("      Name: ${iface.name}")
                    appendLine("      Class: ${iface.interfaceClass}")
                    appendLine("      Subclass: ${iface.interfaceSubclass}")
                    appendLine("      Protocol: ${iface.interfaceProtocol}")
                    appendLine("      Endpoint Count: ${iface.endpointCount}")

                    for (k in 0 until iface.endpointCount) {
                        val endpoint = iface.getEndpoint(k)
                        appendLine("      Endpoint $k:")
                        appendLine("        Endpoint Number: ${endpoint.endpointNumber}")
                        appendLine("        Address: 0x${endpoint.address.toString(16).uppercase()}")
                        appendLine("        Direction: ${endpoint.direction}")
                        appendLine("        Type: ${endpoint.type}")
                        appendLine("        Attributes: ${endpoint.attributes}")
                        appendLine("        Max Packet Size: ${endpoint.maxPacketSize}")
                        appendLine("        Interval: ${endpoint.interval}")
                    }
                }
            }
        }
    }

    private fun isCompatibleDevice(device: UsbDevice): Boolean {
        // Log device info for debugging
        log("Checking device: ${device.deviceName}, Class: ${device.deviceClass}, Subclass: ${device.deviceSubclass}")

        // Check at device level
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true
        }

        // Check at interface level
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            log("  Interface $i: Class: ${usbInterface.interfaceClass}, Subclass: ${usbInterface.interfaceSubclass}")
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                return true
            }
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice, permissionIntent: android.app.PendingIntent) {
        usbManager.requestPermission(device, permissionIntent)
    }

    fun openDevice(device: UsbDevice): android.hardware.usb.UsbDeviceConnection? {
        return usbManager.openDevice(device)
    }
}
