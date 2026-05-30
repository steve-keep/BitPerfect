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
                sb.append("[US-012] Cache Status: ${driveAnalysis.cacheStatus.name}\n")
            }
            if (driveAnalysis.streamingClassification != null) {
                sb.append("[US-013] Streaming: ${driveAnalysis.streamingClassification.name}\n")
            }
            if (driveAnalysis.preferredReadSize != null) {
                sb.append("[US-015] Preferred Read Size: ${driveAnalysis.preferredReadSize}\n")
            }
            sb.append("\n")
        }

        val trackStartEvents = events.filterIsInstance<RipLogEvent.TrackStarted>().associateBy { it.trackNumber }
        val trackCompletionEvents = events.filterIsInstance<RipLogEvent.TrackCompleted>().associateBy { it.trackNumber }
        for ((trackNumber, trackCompleted) in trackCompletionEvents.toSortedMap()) {
            sb.append("Track ${String.format("%02d", trackNumber)}\n")
            sb.append("--------\n")

            // US-025 Detailed rip diagnostics
            val trackEvents = events.filter {
                when (it) {
                    is RipLogEvent.OverlapMismatchDetected -> it.trackNumber == trackNumber
                    is RipLogEvent.RereadEscalated -> it.trackNumber == trackNumber
                    is RipLogEvent.RecoverySucceeded -> it.trackNumber == trackNumber
                    is RipLogEvent.RecoveryFailed -> it.trackNumber == trackNumber
                    is RipLogEvent.TransportAnomaly -> it.trackNumber == trackNumber
                    is RipLogEvent.FastPathStateChanged -> it.trackNumber == trackNumber
                    is RipLogEvent.SampleAlignmentValidated -> it.trackNumber == trackNumber
                    is RipLogEvent.ReadDriftDetected -> it.trackNumber == trackNumber
                    is RipLogEvent.MultiPassComparisonCompleted -> it.trackNumber == trackNumber
                    is RipLogEvent.ReadConsistencyScored -> it.trackNumber == trackNumber
                    is RipLogEvent.TargetedSectorRecoveryLogged -> it.trackNumber == trackNumber
                    else -> false
                }
            }

            sb.append("Confidence: ${trackCompleted.confidence.name}\n")
            sb.append(String.format(java.util.Locale.US, "Duration: %.2fs\n", trackCompleted.durationSeconds))

            // Calculate recovery windows for this track
            val trackRecoveryWindows = trackEvents.filterIsInstance<RipLogEvent.RecoverySucceeded>().count() +
                                       trackEvents.filterIsInstance<RipLogEvent.RecoveryFailed>().count()

            if (trackRecoveryWindows > 0) {
                sb.append("Recovery Windows: $trackRecoveryWindows\n")
            }
            sb.append("Rereads: ${trackCompleted.rereads}\n")
            sb.append("Suspicious Reads: ${trackCompleted.suspiciousReads}\n")
            sb.append("AccurateRip: ${trackCompleted.accurateRipStatus}\n\n")

            // US-014 Atomic read sizing & US-001 Stable overlap verification is covered by general track events
            // Let's print out all the track detailed events.
            if (trackEvents.isNotEmpty()) {
                sb.append("  Diagnostics & Recovery Pipelines\n")
                sb.append("  --------------------------------\n")

                for (event in trackEvents) {
                    when (event) {
                        is RipLogEvent.OverlapMismatchDetected -> {
                            sb.append("  [US-001] Overlap Mismatch: LBA ${event.lbaStart}-${event.lbaEnd}\n")
                        }
                        is RipLogEvent.FastPathStateChanged -> {
                            if (event.enabled) {
                                sb.append("  [US-022] Fast-Path Enabled\n")
                            } else {
                                sb.append("  [US-022] Fast-Path Revoked (Reason: ${event.reason})\n")
                            }
                        }
                        is RipLogEvent.ReadConsistencyScored -> {
                            // Can be too noisy if printed for every read. We will print it only if score < 1.0
                            if (event.score < 1.0f) {
                                sb.append("  [US-004] Consistency Score Dropped: LBA ${event.lbaStart} (Score: ${event.score})\n")
                            }
                        }
                        is RipLogEvent.TargetedSectorRecoveryLogged -> {
                            sb.append("  [US-007] Targeted Sector Recovery: LBA ${event.lbaStart} (${event.sectorCount} sectors, Strategy: ${event.strategy})\n")
                        }
                        is RipLogEvent.ReadDriftDetected -> {
                            sb.append("  [US-005] Read Drift Detected: LBA ${event.lbaStart} (Shift: ${event.shiftSamples} samples, Confidence: ${event.confidence})\n")
                        }
                        is RipLogEvent.MultiPassComparisonCompleted -> {
                            sb.append("  [US-003] Multi-pass Comparison: LBA ${event.lbaStart} (Attempts: ${event.totalAttempts}, Unique: ${event.uniqueCandidates}, Type: ${event.instabilityType}, Resolved: ${event.resolved})\n")
                        }
                        is RipLogEvent.RereadEscalated -> {
                            sb.append("  [US-021] Reread Escalated: LBA ${event.lbaStart}-${event.lbaEnd} (Strategy: ${event.strategy})\n")
                        }
                        is RipLogEvent.RecoverySucceeded -> {
                            sb.append("  [US-021] Recovery Stabilized: LBA ${event.lbaStart}-${event.lbaEnd} (Attempts: ${event.rereadAttempts})\n")
                        }
                        is RipLogEvent.RecoveryFailed -> {
                            sb.append("  [US-021] Recovery Failed: LBA ${event.lbaStart}-${event.lbaEnd} (Attempts: ${event.rereadAttempts})\n")
                        }
                        is RipLogEvent.SampleAlignmentValidated -> {
                            if (!event.valid) {
                                sb.append("  [US-018] Alignment Anomaly: ${event.anomalyType} ")
                                if (event.sampleCount != null) sb.append("(Samples: ${event.sampleCount}) ")
                                if (event.expectedTrim != null && event.actualTrim != null) sb.append("(Trim Expected: ${event.expectedTrim}, Actual: ${event.actualTrim})")
                                sb.append("\n")
                            }
                        }
                        is RipLogEvent.TransportAnomaly -> {
                            sb.append("  [US-025] Transport Anomaly: ${event.anomalyType} - ${event.details}\n")
                        }
                        else -> {}
                    }
                }
                sb.append("\n")
            }
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
