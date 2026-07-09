package com.bitperfect.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UsbDebugLogger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            // Write to public Downloads folder — visible in any file manager
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            logFile = File(downloads, "usb_debug.txt").also {
                it.writeText("=== UsbDebug log started at ${fmt.format(Date())} ===\n")
            }
            android.util.Log.d("USB_DEBUG", "Log initialized at ${logFile?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("USB_DEBUG", "Failed to initialize UsbDebugLogger", e)
        }
    }

    fun log(message: String) {
        val time = fmt.format(Date())
        val formattedMsg = "[$time] $message\n"
        android.util.Log.d("USB_DEBUG", message)
        try {
            logFile?.appendText(formattedMsg)
        } catch (e: Exception) {
            // Silently fail if log cannot be written to avoid crashing
        }
    }
}
