package com.bitperfect.core.utils

import android.util.Log

object AppLogger {
    // A callback for routing logs to other sinks, e.g., OpenTelemetry
    var logCallback: ((String, String, Int, Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        logCallback?.invoke(tag, message, Log.DEBUG, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        Log.e(tag, fullMessage)
        logCallback?.invoke(tag, message, Log.ERROR, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        logCallback?.invoke(tag, message, Log.INFO, null)
    }
}
