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
        sb.append("Application: ${sessionStarted.appVersion}\n")

        val sessionCompleted = events.filterIsInstance<RipLogEvent.SessionCompleted>().lastOrNull()
        if (sessionCompleted != null && sessionCompleted.matchedDiscId1 != null && sessionCompleted.matchedDiscId2 != null) {
            val hexId1 = String.format("%08x", sessionCompleted.matchedDiscId1 and 0xFFFFFFFFL)
            val hexId2 = String.format("%08x", sessionCompleted.matchedDiscId2 and 0xFFFFFFFFL)
            sb.append("Matched Pressing: $hexId1-$hexId2\n")
        }
        sb.append("\n")

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
            sb.append("[US-012]\n")
            sb.append("Cache Status: $cacheStatusStr\n")

            if (driveAnalysis.cacheProbeResult != null) {
                val cpr = driveAnalysis.cacheProbeResult
                val confidence = if (cpr.suspicionScore > 0.8f) "HIGH" else if (cpr.suspicionScore > 0.4f) "MEDIUM" else "LOW"
                sb.append("Confidence: $confidence\n")
                // Approximation, you could calculate total reads from the result but we just log what we have
                sb.append("Suspicion Score: ${String.format("%.2f", cpr.suspicionScore)}\n")
                sb.append("Identical Rereads: ${cpr.identicalRereadCount}\n")
            }
            sb.append("\n")

            val streamingStatusStr = if (p.supportsStreaming) "STABLE_STREAMING" else "UNSTABLE_STREAMING"
            sb.append("[US-013]\n")
            sb.append("Streaming: $streamingStatusStr\n")
            if (driveAnalysis.streamingAnalysisResult != null) {
                val sm = driveAnalysis.streamingAnalysisResult.metrics
                sb.append("Reads Analysed: ${sm.sequentialReadCount}\n")
                sb.append("Stall Events: ${sm.stallEvents}\n")
                sb.append("Longest Stall: ${String.format("%.1f", sm.longestStallMs)}ms\n")
                // Removed Degradation Score as we use stall percentage now
            }
            sb.append("\n")

            sb.append("[US-014]\n")
            sb.append("Preferred Read Size: ${p.preferredReadSize}\n")
            sb.append("Max Reliable Size: ${p.maxReliableReadSize}\n\n")
            if (driveAnalysis.readSizeProfile != null) {
                sb.append("Size Testing\n")
                sb.append("------------\n")
                for (metric in driveAnalysis.readSizeProfile.metrics.sortedBy { it.readSize }) {
                    val passFail = if (metric.transportFailures == 0 && metric.shortReads == 0 && metric.overlapFailures == 0) "PASS" else "FAIL"
                    sb.append("${metric.readSize} sectors : $passFail\n")
                }
                sb.append("\n")
            }

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

            sb.append("Recovery Strategies\n")
            sb.append("-------------------\n")

            var overlapRecovery = 0
            var fullChunkRecovery = 0
            var reducedChunkRecovery = 0
            var driftFocusedRecovery = 0

            for (e in trackEvents.filterIsInstance<RipLogEvent.TargetedSectorRecoveryLogged>()) {
                when {
                    e.strategy.contains("Overlap", ignoreCase = true) -> overlapRecovery++
                    e.strategy.contains("FullChunk", ignoreCase = true) -> fullChunkRecovery++
                    e.strategy.contains("Reduced", ignoreCase = true) -> reducedChunkRecovery++
                    e.strategy.contains("Drift", ignoreCase = true) -> driftFocusedRecovery++
                }
            }

            sb.append("OverlapRecoveryStrategy: $overlapRecovery\n")
            sb.append("FullChunkRecoveryStrategy: $fullChunkRecovery\n")
            sb.append("ReducedChunkRecoveryStrategy: $reducedChunkRecovery\n")
            sb.append("DriftFocusedRecoveryStrategy: $driftFocusedRecovery\n\n")

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


        // Calculate SHA-256 hash for verification
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(sb.toString().toByteArray(StandardCharsets.UTF_8))
            val hashString = hashBytes.joinToString("") { "%02x".format(it) }
            sb.append("End of log\n")
            sb.append("Log Hash (SHA-256): $hashString\n")
        } catch (e: Exception) {
            AppLogger.e("ForensicRipLogger", "Failed to calculate log hash", e)
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
