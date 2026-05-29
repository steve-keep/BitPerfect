package com.bitperfect.app.ripping.logging

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.core.utils.AppLogger
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

interface ForensicRipLogger {
    fun record(event: RipLogEvent)
    fun finalize(context: Context, outputDirectory: DocumentFile)
}

class DefaultForensicRipLogger : ForensicRipLogger {
    private val events = mutableListOf<RipLogEvent>()

    override fun record(event: RipLogEvent) {
        events.add(event)
    }

    override fun finalize(context: Context, outputDirectory: DocumentFile) {
        val sessionStarted = events.filterIsInstance<RipLogEvent.SessionStarted>().firstOrNull()
        if (sessionStarted == null) {
            AppLogger.w("ForensicRipLogger", "No SessionStarted event found, skipping log generation")
            return
        }

        val sb = StringBuilder()

        sb.append("BitPerfect Forensic Rip Log\n")
        sb.append("================================\n\n")

        sb.append("Session\n")
        sb.append("-------\n")
        sb.append("Timestamp: ${sessionStarted.timestampIso}\n")
        sb.append("Device: ${sessionStarted.deviceModel}\n")
        sb.append("Android: ${sessionStarted.androidVersion}\n")
        sb.append("Mode: ${if (sessionStarted.mode == RipMode.SECURE) "Secure" else sessionStarted.mode.name}\n\n")

        sb.append("Drive\n")
        sb.append("-----\n")
        sb.append("Vendor: ${sessionStarted.driveVendor}\n")
        sb.append("Model: ${sessionStarted.driveModel}\n")
        if (sessionStarted.driveFirmware != null) {
            sb.append("Firmware: ${sessionStarted.driveFirmware}\n")
        }
        sb.append("\n")

        val driveAnalysis = events.filterIsInstance<RipLogEvent.DriveAnalysisCompleted>().lastOrNull()
        if (driveAnalysis != null) {
            sb.append("Drive Intelligence\n")
            sb.append("------------------\n")
            if (driveAnalysis.cacheStatus != null) {
                sb.append("Cache Status: ${driveAnalysis.cacheStatus.name}\n")
            }
            if (driveAnalysis.streamingClassification != null) {
                sb.append("Streaming: ${driveAnalysis.streamingClassification.name}\n")
            }
            if (driveAnalysis.preferredReadSize != null) {
                sb.append("Preferred Read Size: ${driveAnalysis.preferredReadSize}\n")
            }
            sb.append("\n")
        }

        val trackStartEvents = events.filterIsInstance<RipLogEvent.TrackStarted>().associateBy { it.trackNumber }
        val trackCompletionEvents = events.filterIsInstance<RipLogEvent.TrackCompleted>().associateBy { it.trackNumber }
        val recoveryEvents = events.filter {
            it is RipLogEvent.OverlapMismatchDetected ||
            it is RipLogEvent.RereadEscalated ||
            it is RipLogEvent.RecoverySucceeded ||
            it is RipLogEvent.RecoveryFailed ||
            it is RipLogEvent.TransportAnomaly
        }

        for ((trackNumber, trackCompleted) in trackCompletionEvents.toSortedMap()) {
            sb.append("Track ${String.format("%02d", trackNumber)}\n")
            sb.append("--------\n")
            sb.append("Confidence: ${trackCompleted.confidence.name}\n")
            sb.append(String.format(java.util.Locale.US, "Duration: %.2fs\n", trackCompleted.durationSeconds))

            // Calculate recovery windows for this track
            val trackRecoveryWindows = events.filterIsInstance<RipLogEvent.RecoverySucceeded>().count { it.trackNumber == trackNumber } +
                                       events.filterIsInstance<RipLogEvent.RecoveryFailed>().count { it.trackNumber == trackNumber }

            if (trackRecoveryWindows > 0) {
                sb.append("Recovery Windows: $trackRecoveryWindows\n")
            }
            sb.append("Rereads: ${trackCompleted.rereads}\n")
            sb.append("Suspicious Reads: ${trackCompleted.suspiciousReads}\n")
            sb.append("AccurateRip: ${trackCompleted.accurateRipStatus}\n\n")
        }

        if (recoveryEvents.isNotEmpty()) {
            sb.append("Recovery Events\n")
            sb.append("---------------\n")

            var currentMismatch: RipLogEvent.OverlapMismatchDetected? = null

            for (event in recoveryEvents) {
                when (event) {
                    is RipLogEvent.OverlapMismatchDetected -> {
                        currentMismatch = event
                        sb.append("LBA ${event.lbaStart}-${event.lbaEnd} overlap mismatch\n")
                    }
                    is RipLogEvent.RereadEscalated -> {
                        // Only log if it's a new escalation strategy that isn't overlap_recovery?
                        // The user said "Recovery stabilized after X rereads".
                        // Let's just keep it simple based on the example.
                    }
                    is RipLogEvent.RecoverySucceeded -> {
                        sb.append("Recovery stabilized after ${event.rereadAttempts} rereads\n")
                        currentMismatch = null
                    }
                    is RipLogEvent.RecoveryFailed -> {
                        sb.append("Recovery failed after ${event.rereadAttempts} rereads\n")
                        currentMismatch = null
                    }
                    is RipLogEvent.TransportAnomaly -> {
                        sb.append("Track ${event.trackNumber}: ${event.anomalyType} - ${event.details}\n")
                    }
                    else -> {}
                }
            }
            sb.append("\n")
        }

        try {
            outputDirectory.findFile("bitperfect-rip-log.txt")?.delete()
            val destFile = outputDirectory.createFile("text/plain", "bitperfect-rip-log.txt")
            if (destFile != null) {
                context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                    OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        writer.write(sb.toString())
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("ForensicRipLogger", "Failed to write bitperfect-rip-log.txt", e)
        }
    }
}
