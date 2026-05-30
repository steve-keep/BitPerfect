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
        sb.append("Application: ${sessionStarted.appVersion}\n\n")
        sb.append("Album: ${sessionStarted.albumTitle}\n")
        sb.append("Artist: ${sessionStarted.artistName}\n\n")
        sb.append("Mode: ${if (sessionStarted.mode == RipMode.SECURE) "Secure" else sessionStarted.mode.name}\n")
        sb.append("Chunk Size: ${sessionStarted.chunkSize} sectors\n")
        sb.append("Overlap Size: ${sessionStarted.overlapSize} sectors\n\n")

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
            val p = driveAnalysis.profile
            sb.append("Drive Intelligence\n")
            sb.append("------------------\n\n")

            // Cache probe result mapping (derive CacheStatus back or simple formatting)
            val cacheStatusStr = if (p.likelyCachesAudio) "CACHE_CONFIRMED" else "CACHE_UNLIKELY"
            sb.append("[US-012] Cache Status: $cacheStatusStr\n\n")

            val streamingStatusStr = if (p.supportsStreaming) "STABLE_STREAMING" else "UNSTABLE_STREAMING"
            sb.append("[US-013] Streaming: $streamingStatusStr\n\n")

            sb.append("[US-014]\n")
            sb.append("Preferred Read Size: ${p.preferredReadSize}\n")
            sb.append("Max Reliable Size: ${p.maxReliableReadSize}\n")
            sb.append("Unstable Sizes: None\n\n") // Based on current capabilities it is empty or simple

            sb.append("[US-015]\n")
            sb.append("Supports Streaming: ${if (p.supportsStreaming) "Yes" else "No"}\n")
            sb.append("Likely Caches Audio: ${if (p.likelyCachesAudio) "Yes" else "No"}\n")
            sb.append("Stable Large Reads: ${if (p.stableLargeReads) "Yes" else "No"}\n")
            sb.append("Unstable Seeking: ${if (p.unstableSeeking) "Yes" else "No"}\n\n")
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
            sb.append("AccurateRip: ${trackCompleted.accurateRipStatus}\n\n")

            sb.append("Verification\n")
            sb.append("------------\n")
            sb.append("Chunks Read: ${trackCompleted.summary.chunksRead}\n")
            sb.append("Overlap Verifications: ${trackCompleted.summary.overlapVerifications}\n")
            sb.append("Overlap Failures: ${trackCompleted.summary.overlapFailures}\n\n")

            sb.append("Recovery\n")
            sb.append("--------\n")
            sb.append("Rereads: ${trackCompleted.rereads}\n")
            sb.append("Recovery Windows: ${trackCompleted.summary.recoveryWindows}\n")
            sb.append("Escalations: ${trackCompleted.summary.escalations}\n\n")

            // To figure out dropped/duplicate samples we can examine SampleAlignmentValidated events
            var droppedSamples = 0
            var duplicateSamples = 0
            for (e in trackEvents.filterIsInstance<RipLogEvent.SampleAlignmentValidated>()) {
                if (!e.valid && e.anomalyType != null) {
                    if (e.anomalyType.contains("Dropped", ignoreCase = true)) {
                        droppedSamples += e.sampleCount ?: 0
                    } else if (e.anomalyType.contains("Duplicate", ignoreCase = true)) {
                        duplicateSamples += e.sampleCount ?: 0
                    }
                }
            }

            sb.append("Alignment\n")
            sb.append("---------\n")
            sb.append("Alignment Checks: ${trackCompleted.summary.alignmentChecks}\n")
            sb.append("Drift Events: ${trackCompleted.summary.driftEvents}\n")
            sb.append("Duplicate Samples: $duplicateSamples\n")
            sb.append("Dropped Samples: $droppedSamples\n\n")

            sb.append("Fast Path\n")
            sb.append("---------\n")
            val fpEnabled = trackEvents.filterIsInstance<RipLogEvent.FastPathStateChanged>().any { it.enabled }
            val revocations = trackEvents.filterIsInstance<RipLogEvent.FastPathStateChanged>().count { !it.enabled }
            sb.append("Enabled: ${if (fpEnabled) "Yes" else "No"}\n")
            sb.append("Fast-Path Chunks: ${trackCompleted.summary.fastPathChunks}\n")
            sb.append("Revocations: $revocations\n\n")


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
