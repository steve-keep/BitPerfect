package com.bitperfect.app

import android.content.Context
import android.os.Build
import java.io.File

object DebugReportManager {

    fun generateFullReport(context: Context, sessionLogs: List<String>): String {
        val sb = StringBuilder()

        sb.append("=== BITPERFECT DEBUG REPORT ===\n")
        sb.append("App Version: ${getAppVersion(context)}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("\n")

        sb.append("--- CRASH HISTORY ---\n")
        val crashReport = CrashReporter.getCrashReport(context)
        sb.append(crashReport ?: "No crash history found.\n")
        sb.append("\n")

        sb.append("--- LATEST RIP LOG ---\n")
        sb.append(getLatestRipLog(context) ?: "No rip log found.\n")
        sb.append("\n")

        sb.append("--- SESSION LOGS ---\n")
        sessionLogs.forEach { sb.append("> $it\n") }
        sb.append("\n")

        sb.append("=== END OF REPORT ===\n")

        return sb.toString()
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun getLatestRipLog(context: Context): String? {
        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        // Search for rip_log.txt recursively in the output directory
        return findRipLog(outputDir)
    }

    private fun findRipLog(dir: File): String? {
        val files = dir.listFiles() ?: return null
        // Sort by last modified to find the latest
        val sortedFiles = files.sortedByDescending { it.lastModified() }

        for (file in sortedFiles) {
            if (file.isDirectory) {
                val found = findRipLog(file)
                if (found != null) return found
            } else if (file.name == "rip_log.txt") {
                return file.readText()
            }
        }
        return null
    }
}
