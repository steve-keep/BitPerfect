package com.bitperfect.core.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbDeviceManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun getCompatibleDevices(): List<UsbDevice> {
        val deviceList = usbManager.deviceList
        val devices = deviceList.values.filter { isCompatibleDevice(it) }
        for (device in devices) {
            Log.d("UsbDeviceManager", "\nUSB Descriptor Info:\n" + getDeviceInfo(device))
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
        }
    }

    private fun isCompatibleDevice(device: UsbDevice): Boolean {
        // Log device info for debugging
        Log.d("UsbDeviceManager", "Checking device: ${device.deviceName}, Class: ${device.deviceClass}, Subclass: ${device.deviceSubclass}")

        // Check at device level
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true
        }

        // Check at interface level
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            Log.d("UsbDeviceManager", "  Interface $i: Class: ${usbInterface.interfaceClass}, Subclass: ${usbInterface.interfaceSubclass}")
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
