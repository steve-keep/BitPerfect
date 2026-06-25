package com.bitperfect.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsbDacDebugLogger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            logFile = File(downloads, "usb_dac_debug.txt").also {
                it.writeText("=== UsbDacDebug log started at ${fmt.format(Date())} ===\n")
            }
            android.util.Log.d("USB_DAC_DEBUG", "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("USB_DAC_DEBUG", "Failed to init log file", e)
        }
    }

    fun log(message: String) {
        val line = "${fmt.format(Date())} $message\n"
        android.util.Log.d("USB_DAC_DEBUG", message)
        try {
            logFile?.appendText(line)
        } catch (e: Exception) {
            android.util.Log.e("USB_DAC_DEBUG", "Failed to write log", e)
        }
    }
}
