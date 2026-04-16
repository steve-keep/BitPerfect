package com.bitperfect.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val CRASH_FILE_NAME = "crash_report.txt"
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return

        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashReport(appContext, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        isInitialized = true
    }

    private fun saveCrashReport(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = """
            |--- CRASH REPORT ---
            |Timestamp: $timestamp
            |Stack Trace:
            |$stackTrace
            |---------------------
            |
        """.trimMargin()

        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            file.appendText(report)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCrashReport(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun clearCrashReport(context: Context) {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
    }
}
