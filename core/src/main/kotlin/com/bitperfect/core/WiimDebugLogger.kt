package com.bitperfect.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WiimDebugLogger {

    private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile = File(context.getExternalFilesDir(null), "wiim_debug.txt").also {
            it.writeText("=== WiimDebug log started at ${fmt.format(Date())} ===\n")
        }
    }

    fun log(message: String) {
        val line = "${fmt.format(Date())} $message\n"
        android.util.Log.d("WIIM_DEBUG", message)
        try {
            logFile?.appendText(line)
        } catch (e: Exception) {
            // ignore
        }
    }
}
