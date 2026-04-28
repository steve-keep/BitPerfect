package com.bitperfect.app

import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val stackTrace = sw.toString()

            val prefs = context.getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_crash", stackTrace).commit() // Use commit() to ensure it saves synchronously before dying
        } catch (ex: Exception) {
            Log.e("CrashHandler", "Error saving crash log", ex)
        } finally {
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
