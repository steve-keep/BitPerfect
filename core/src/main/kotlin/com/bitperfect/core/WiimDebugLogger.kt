package com.bitperfect.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WiimDebugLogger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            // Write to public Downloads folder — visible in any file manager
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            logFile = File(downloads, "wiim_debug.txt").also {
                it.writeText("=== WiimDebug log started at ${fmt.format(Date())} ===\n")
            }
            android.util.Log.d("WIIM_DEBUG", "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("WIIM_DEBUG", "Failed to init log file", e)
        }
    }

    fun log(message: String) {
        val line = "${fmt.format(Date())} $message\n"
        android.util.Log.d("WIIM_DEBUG", message)
        try {
            logFile?.appendText(line)
        } catch (e: Exception) {
            android.util.Log.e("WIIM_DEBUG", "Failed to write log", e)
        }
    }
}
