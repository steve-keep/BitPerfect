package com.bitperfect.app.usb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.app.BuildConfig

import com.bitperfect.app.ripping.logging.DefaultForensicRipLogger
import com.bitperfect.app.ripping.logging.RipLogEvent
import com.bitperfect.app.ripping.logging.RipMode
import com.bitperfect.app.ripping.capability.DefaultDriveProfiler
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.LyricsFetchResult
import com.bitperfect.core.services.AccurateRipService
import com.bitperfect.core.services.AccurateRipVerifier
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.services.AccurateRipDiscPressing
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.AppLogger
import com.bitperfect.app.ripping.paranoia.OverlapVerifier
import com.bitperfect.app.ripping.paranoia.RereadEngine
import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import com.bitperfect.app.ripping.paranoia.RereadRecoveryResult
import com.bitperfect.app.ripping.paranoia.strategy.OverlapRecoveryStrategy
import com.bitperfect.app.ripping.paranoia.strategy.FullChunkRecoveryStrategy
import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.paranoia.RipConfidenceEvaluator
import com.bitperfect.app.ripping.paranoia.SuspiciousRead
import com.bitperfect.app.ripping.paranoia.SampleAlignmentValidator
import com.bitperfect.app.ripping.paranoia.AlignmentIssue
import com.bitperfect.app.ripping.profiler.ReadSizeMetricsCollector
import com.bitperfect.app.ripping.profiler.DefaultAtomicReadProfiler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream

import com.bitperfect.core.utils.computeAccurateRipDiscId
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.BufferedReader
import com.bitperfect.core.models.LyricsResult
import com.bitperfect.app.usb.TrackRipResult
import com.bitperfect.app.usb.TrackRipStats
import com.bitperfect.core.models.TocEntry


data class OutputDirs(val artistDir: DocumentFile, val albumDir: DocumentFile)

fun interface DirectoryResolver {
    fun fromTreeUri(context: Context, uri: Uri): DocumentFile?
}

internal fun setupOutputDirectory(
    context: Context,
    outputFolderUriString: String,
    artistName: String,
    albumTitle: String,
    resolver: DirectoryResolver = DirectoryResolver { ctx, uri -> DocumentFile.fromTreeUri(ctx, uri) }
): OutputDirs {
    val baseUri = Uri.parse(outputFolderUriString)
    val parentDir = resolver.fromTreeUri(context, baseUri)
    if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
        throw java.io.IOException("Invalid output directory: $outputFolderUriString")
    }
    val safeArtist = artistName.replace("/", "_")
    val safeAlbum = albumTitle.replace("/", "_")
    val artistDir = parentDir.findFile(safeArtist)
        ?: parentDir.createDirectory(safeArtist)
        ?: throw java.io.IOException("Could not create artist directory: $safeArtist")
    val albumDir = artistDir.findFile(safeAlbum)
        ?: artistDir.createDirectory(safeAlbum)
        ?: throw java.io.IOException("Could not create album directory: $safeAlbum")
    return OutputDirs(artistDir, albumDir)
}


enum class RipStatus {
    IDLE, RIPPING, VERIFYING, SUCCESS, UNVERIFIED, WARNING, ERROR, CANCELLED
}

data class TrackRipState(
    val trackNumber: Int,
    val discNumber: Int = 1,
    val progress: Float = 0f,
    val status: RipStatus = RipStatus.IDLE,
    val accurateRipUrl: String? = null,
    val computedChecksumV1: Long? = null,
    val computedChecksumV2: Long? = null,
    val expectedChecksumsV1: List<Long> = emptyList(),
    val expectedChecksumsV2: List<Long> = emptyList(),
    val matchedVersion: Int? = null,
    val arConfidence: Int? = null,
    val errorMessage: String? = null,
    // Diagnostic fields
    val startLba: Int = 0,
    val endLba: Int = 0,
    val totalSectors: Int = 0,
    val sectorsRead: Int = 0,
    val totalSamples: Long = 0L,
    val durationSeconds: Double = 0.0,
    val extractionTimeSeconds: Double = 0.0,
    val confidence: RipConfidence = RipConfidence.HIGH,
    val suspiciousRegions: List<com.bitperfect.app.ripping.paranoia.SuspiciousRead> = emptyList()
)

class RipManager(
    private val context: Context,
    private val outputFolderUriString: String,
    private val toc: DiscToc,
    private val metadata: DiscMetadata,
    private val expectedChecksums: List<AccurateRipDiscPressing>,
    private val artworkBytes: ByteArray?,
    private val lyricsMap: Map<Int, LyricsFetchResult> = emptyMap(),
    private val driveVendor: String,
    private val driveProduct: String,
    initialTracks: List<Int>,
    previousStates: Map<Int, TrackRipState>? = null
) {
    private val activePressingCandidates: MutableSet<AccurateRipDiscPressing> = expectedChecksums.toMutableSet()
    private val _trackStates = MutableStateFlow<Map<Int, TrackRipState>>(
        toc.tracks.associate { track ->
            val prevState = previousStates?.get(track.trackNumber)
            val isBeingRescanned = initialTracks.contains(track.trackNumber)

            val initialState = if (prevState != null && !isBeingRescanned) {
                prevState
            } else {
                TrackRipState(trackNumber = track.trackNumber, discNumber = metadata.discNumber ?: 1)
            }

            track.trackNumber to initialState
        }
    )
    val trackStates: StateFlow<Map<Int, TrackRipState>> = _trackStates

    var isCancelled = false
        private set
    private val audioDbRepository = com.bitperfect.core.services.AudioDbRepository()

    private val verifier = AccurateRipVerifier()
    private val confidenceEvaluator = RipConfidenceEvaluator()
    private val alignmentValidator = SampleAlignmentValidator()

    private var albumDir: DocumentFile? = null

    private val trackQueue = java.util.concurrent.ConcurrentLinkedQueue(initialTracks)

    fun queueTrack(trackNumber: Int) {
        if (!trackQueue.contains(trackNumber)) {
            trackQueue.offer(trackNumber)
            updateTrackState(trackNumber, RipStatus.IDLE, 0f)
        }
    }

internal fun verifyTrack(
    trackNumber: Int,
    checksumV1: Long,
    checksumV2: Long,
    activePressingCandidates: MutableSet<com.bitperfect.core.services.AccurateRipDiscPressing>,
    expectedChecksums: List<com.bitperfect.core.services.AccurateRipDiscPressing>
): TrackVerificationResult {
    // Verify checksum — prefer V2 match, fall back to V1.
    // A pressing must match on whichever version its database entry carries.
    activePressingCandidates.retainAll { pressing ->
        val dbTrack = pressing.tracks[trackNumber] ?: return@retainAll false
        if (dbTrack.crcV2 != null) {
            dbTrack.crcV2 == checksumV2
        } else {
            dbTrack.crcV1 == checksumV1
        }
    }

    val matchedVersion = if (activePressingCandidates.isNotEmpty()) {
        if (activePressingCandidates.any { it.tracks[trackNumber]?.crcV2 == checksumV2 }) 2 else 1
    } else {
        null
    }

    val matchedConfidence: Int? = if (activePressingCandidates.isNotEmpty()) {
        activePressingCandidates
            .mapNotNull { it.tracks[trackNumber]?.confidence }
            .maxOrNull()
    } else null

    // Always derive expected hash lists from the full original database — never
    // from the filtered candidates — so the log always shows what AR actually holds.
    val allExpectedV1 = expectedChecksums.mapNotNull { it.tracks[trackNumber]?.crcV1 }.distinct()
    val allExpectedV2 = expectedChecksums.mapNotNull { it.tracks[trackNumber]?.crcV2 }.distinct()
    val hasExpected = allExpectedV1.isNotEmpty() || allExpectedV2.isNotEmpty()

    val finalStatus = if (activePressingCandidates.isNotEmpty()) {
        RipStatus.SUCCESS
    } else if (!hasExpected) {
        RipStatus.UNVERIFIED
    } else {
        RipStatus.WARNING
    }

    if (!hasExpected) {
        AppLogger.d("RipManager", "Track $trackNumber not in AccurateRip database.")
    } else if (matchedVersion == null) {
        AppLogger.w("RipManager", "Checksum mismatch for track $trackNumber.")
    }

    return TrackVerificationResult(
        finalStatus = finalStatus,
        matchedVersion = matchedVersion,
        matchedConfidence = matchedConfidence,
        allExpectedV1 = allExpectedV1,
        allExpectedV2 = allExpectedV2,
        hasExpected = hasExpected
    )
}

internal fun writeTrackFile(
    context: android.content.Context,
    destFile: androidx.documentfile.provider.DocumentFile,
    metadataBytes: ByteArray,
    tempFile: java.io.File
): WriteTrackResult {
    var outputStream: java.io.OutputStream? = null
    return try {
        val rawStream = context.contentResolver.openOutputStream(destFile.uri)
            ?: return WriteTrackResult.Failed("openOutputStream returned null for ${destFile.name}")
        outputStream = java.io.BufferedOutputStream(rawStream, 1024 * 1024)
        outputStream.write(metadataBytes)
        tempFile.inputStream().use { it.copyTo(outputStream) }
        outputStream.flush()
        WriteTrackResult.Success
    } catch (e: Exception) {
        destFile.delete()
        WriteTrackResult.Failed(e.message ?: "Unknown write error")
    } finally {
        try {
            outputStream?.close()
        } catch (e: Exception) {
            // Ignore close exceptions, already returning result or failing
        }
    }
}

internal suspend fun ripTrack(
    context: android.content.Context,
    trackNumber: Int,
    i: Int,
    entry: com.bitperfect.core.models.TocEntry,
    nextLba: Int,
    totalSectors: Int,
    totalSamples: Long,
    trackTitle: String,
    lyricsResult: com.bitperfect.core.models.LyricsResult?,
    destFile: androidx.documentfile.provider.DocumentFile,
    config: RipConfig,
    toc: com.bitperfect.core.models.DiscToc,
    metadata: com.bitperfect.core.models.DiscMetadata,
    accurateRipUrl: String?,
    artworkBytes: ByteArray?,
    expectedChecksums: List<com.bitperfect.core.services.AccurateRipDiscPressing>,
    activePressingCandidates: Set<com.bitperfect.core.services.AccurateRipDiscPressing>,
    session: UsbReadSession,
    metricsCollector: com.bitperfect.app.ripping.profiler.ReadSizeMetricsCollector,
    logger: com.bitperfect.app.ripping.logging.DefaultForensicRipLogger,
    incomingOverreadBuffer: ByteArray?,
    isCancelled: () -> Boolean,
    trackStartTimeMs: Long,
    onProgress: (Float, com.bitperfect.app.ripping.paranoia.RipConfidence?, List<com.bitperfect.app.ripping.paranoia.SuspiciousRead>?) -> Unit
): TrackRipResult {
    var outputStream: java.io.OutputStream? = null
    var tempOutputStream: java.io.OutputStream? = null
    var encoder: FlacEncoder? = null
    var finalChecksumV1 = 0L
    var finalChecksumV2 = 0L
    var sectorsRead = 0

    var lastCommittedPcm: ByteArray? = null
    var totalOverlapTrimmedSamples = 0L
    var expectedTotalOverlapTrimmedSamples = 0L

    var trackChunksRead = 0
    var trackOverlapVerifications = 0
    var trackOverlapFailures = 0
    var trackAlignmentChecks = 0
    var trackDriftEvents = 0
    var trackRecoveryWindows = 0
    var trackEscalations = 0
    var trackFastPathChunks = 0

    val streamingReads = mutableListOf<com.bitperfect.app.ripping.streaming.SequentialReadTelemetry>()
    var overreadBuffer = incomingOverreadBuffer

    var currentConfidence = com.bitperfect.app.ripping.paranoia.RipConfidence.HIGH
    val currentSuspiciousRegions = mutableListOf<com.bitperfect.app.ripping.paranoia.SuspiciousRead>()

            val tempFile = java.io.File(context.cacheDir, "temp_rip_$trackNumber.flac")
            var ripSucceeded = false
            try {
                tempOutputStream = BufferedOutputStream(java.io.FileOutputStream(tempFile), 1024 * 1024)

                encoder = FlacEncoder(tempOutputStream, writeHeader = false)
                encoder.start()

                val isFirstTrack = (i == 0)
                val isLastTrack  = (i == toc.tracks.size - 1)

                val checksumAccumulator = ChecksumAccumulator(
                    totalSamples  = totalSamples,
                    isFirstTrack  = isFirstTrack,
                    isLastTrack   = isLastTrack
                )

                val analyser = AudioAnalyser()

                AppLogger.d("RipManager", "[US-014] Atomic read sizing configured. ChunkSize=${config.chunkSize} sectors, Overlap=${config.overlapSize} sectors")
                var advanceSize = config.chunkSize - config.overlapSize

                val overlapVerifier = com.bitperfect.app.ripping.paranoia.OverlapVerifier(
                    overlapSizeSectors = config.overlapSize
                )
                val rereadEngine = com.bitperfect.app.ripping.paranoia.RereadEngine(verifier = overlapVerifier, maxRereads = 6)
                val recoveryCoordinator = com.bitperfect.app.ripping.paranoia.RecoveryCoordinator(
                    rereadEngine = rereadEngine,
                    verifier = overlapVerifier
                )
                val fastPathEvaluator = com.bitperfect.app.ripping.paranoia.fastpath.FastPathEvaluator()
                val streamingBehaviorAnalyzer = com.bitperfect.app.ripping.streaming.DefaultStreamingBehaviorAnalyzer()
                val streamingReads = mutableListOf<com.bitperfect.app.ripping.streaming.SequentialReadTelemetry>()

                var wasPreviousReadRecovery = false

                var pendingChunk: VerifiedChunk? = null

                val lbaStart = entry.lba + config.tocOffset

                val (firstLba, lastReadableLba) = ripLbaRange(
                    trackLba      = entry.lba,
                    nextLba       = nextLba,
                    tocOffset     = config.tocOffset,
                    pregapOffset  = toc.pregapOffset,
                    isLastTrack   = isLastTrack
                )
                // Log if LBA 0 clamping occurred (Track 1 on standard disc with zero drive offset)
                if (firstLba == 1 && (entry.lba + config.tocOffset - toc.pregapOffset) <= 0) {
                    AppLogger.w("RipManager", "LBA 0 clamp applied for track $trackNumber — " +
                        "firstLba adjusted from ${entry.lba + config.tocOffset - toc.pregapOffset} to 1")
                }

                var isFirstSector = true

                if (overreadBuffer != null) {
                    encoder.encode(overreadBuffer!!)
                    checksumAccumulator.accumulate(overreadBuffer!!)
                    analyser.feed(overreadBuffer!!)
                    sectorsRead = 1
                    isFirstSector = false
                }

                val effectiveTotalSectors = lastReadableLba - firstLba + 1

                val expectedTotalSectors = if (isLastTrack) totalSectors - config.tocOffset else totalSectors
                val missingStartSectors = expectedTotalSectors - effectiveTotalSectors

                if (missingStartSectors > 0) {
                    AppLogger.w("RipManager", "Prepending $missingStartSectors sectors of silence due to LBA clamp")
                    val silenceBytes = ByteArray(missingStartSectors * 2352)

                    val missingStartSectors = if (firstLba < 0) -firstLba else 0
                    val trimmedSilence = if (isFirstSector && config.skipBytes > 0) {
                        silenceBytes.copyOfRange(config.skipBytes, silenceBytes.size)
                    } else {
                        silenceBytes
                    }

                    encoder.encode(trimmedSilence)
                    checksumAccumulator.accumulate(trimmedSilence)
                    analyser.feed(trimmedSilence)

                    isFirstSector = false // Ensure the physical read loop doesn't trim again
                }


                while (sectorsRead < effectiveTotalSectors && !isCancelled()) {
                    val sectorsToRead = minOf(config.chunkSize, effectiveTotalSectors - sectorsRead)

                    metricsCollector.recordAttempt(config.chunkSize)

                    val readStartLba = firstLba + sectorsRead
                    val readStartTime = android.os.SystemClock.elapsedRealtime()
                    val pcmData = session.readSectors(readStartLba, sectorsToRead)
                    val readDuration = android.os.SystemClock.elapsedRealtime() - readStartTime

                    if (pcmData != null) {
                        streamingReads.add(
                            com.bitperfect.app.ripping.streaming.SequentialReadTelemetry(
                                startLba = readStartLba,
                                endLba = readStartLba + sectorsToRead,
                                durationMs = readDuration.toDouble(),
                                wasRecoveryRead = false,
                                followedSeekRecovery = wasPreviousReadRecovery
                            )
                        )
                        metricsCollector.recordSuccessfulRead(config.chunkSize)
                        wasPreviousReadRecovery = false
                        val sectorsActuallyRead = pcmData.size / 2352
                        if (sectorsActuallyRead < sectorsToRead) {
                            metricsCollector.recordShortRead(config.chunkSize)
                            AppLogger.w("RipManager", "Short read at LBA ${firstLba + sectorsRead}: " +
                                "got $sectorsActuallyRead of $sectorsToRead sectors")
                            logger.record(RipLogEvent.TransportAnomaly(
                                trackNumber = trackNumber,
                                anomalyType = "SHORT_READ",
                                details = "LBA ${firstLba + sectorsRead}: got $sectorsActuallyRead of $sectorsToRead sectors"
                            ))
                        }

                        // Determine if this is the final read
                        // advanceSize is the stride. If reading advanceSize puts us past or at the end, it's final.


                        val currentLba = firstLba + sectorsRead

                        var currentChunk = VerifiedChunk(
                            startLba = currentLba,
                            endLba = currentLba + sectorsActuallyRead,
                            pcm = pcmData,
                            overlapHead = overlapVerifier.extractOverlapHead(pcmData),
                            overlapTail = overlapVerifier.extractOverlapTail(pcmData),
                            rereadCount = 0
                        )
                        trackChunksRead++

                        var currentChunkConfidence = RipConfidence.HIGH

                        var committedPcm: ByteArray? = null

                        if (pendingChunk != null) {
                            val pChunk = pendingChunk!!
                            val match = overlapVerifier.verifyOverlap(pChunk.overlapTail, currentChunk.overlapHead)
                            trackOverlapVerifications++

                            if (match) {
                                AppLogger.d("RipManager", "overlap_match track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - config.overlapSize} confidence=HIGH")
                                val fpWasEligible = fastPathEvaluator.state.eligible
                                trackFastPathChunks++
                                fastPathEvaluator.reportMatch(currentChunkConfidence)
                                if (!fpWasEligible && fastPathEvaluator.state.eligible) {
                                    logger.record(RipLogEvent.FastPathStateChanged(trackNumber, true))
                                }
                                logger.record(RipLogEvent.ReadConsistencyScored(
                                    trackNumber = trackNumber,
                                    lbaStart = currentChunk.startLba,
                                    score = 1.0f
                                ))
                                committedPcm = overlapVerifier.commitVerifiedAudio(pChunk, isFinal = false)

                                val trimmedSamples = (pChunk.pcm.size - committedPcm.size) / 4
                                totalOverlapTrimmedSamples += trimmedSamples
                                expectedTotalOverlapTrimmedSamples += overlapVerifier.overlapSizeBytes / 4
                            } else {
                                trackOverlapFailures++
                                metricsCollector.recordOverlapFailure(config.chunkSize)
                                AppLogger.w("RipManager", "overlap_mismatch track=$trackNumber lba=${currentChunk.startLba} overlapStartLba=${pChunk.endLba - config.overlapSize}")
                                logger.record(RipLogEvent.OverlapMismatchDetected(
                                    trackNumber = trackNumber,
                                    lbaStart = currentChunk.startLba,
                                    lbaEnd = pChunk.endLba
                                ))
                                val fpWasEligible2 = fastPathEvaluator.state.eligible
                                fastPathEvaluator.reportMismatch()
                                if (fpWasEligible2 && !fastPathEvaluator.state.eligible) {
                                    logger.record(RipLogEvent.FastPathStateChanged(trackNumber, false, "OverlapMismatch"))
                                }

                                trackRecoveryWindows++
                                val recoveryResult = recoveryCoordinator.recover(
                                    previousVerifiedChunk = pChunk,
                                    failedChunk = currentChunk,
                                    readChunk = { lba, count ->
                                        val newPcm = session.readSectors(lba, count)
                                        if (newPcm != null) {
                                            wasPreviousReadRecovery = true
                                            trackChunksRead++
                                            VerifiedChunk(
                                                startLba = lba,
                                                endLba = lba + (newPcm.size / 2352),
                                                pcm = newPcm,
                                                overlapHead = overlapVerifier.extractOverlapHead(newPcm),
                                                overlapTail = overlapVerifier.extractOverlapTail(newPcm),
                                                rereadCount = 0
                                            )
                                        } else null
                                    }
                                )

                                val metadataHistory = when (recoveryResult) {
                                    is RereadRecoveryResult.Recovered -> recoveryResult.metadataHistory
                                    is RereadRecoveryResult.Failed -> recoveryResult.metadataHistory
                                    else -> emptyList()
                                }

                                AppLogger.d("RipManager", "targeted_recovery_started track=$trackNumber lba=${currentChunk.startLba}")

                                var finalMetadata = metadataHistory.lastOrNull()
                                var totalAttempts = 0

                                for (metadata in metadataHistory) {
                                    logger.record(RipLogEvent.RereadEscalated(
                                        trackNumber = trackNumber,
                                        lbaStart = currentChunk.startLba,
                                        lbaEnd = currentChunk.endLba,
                                        strategy = metadata.strategy
                                    ))
                                    val winSize = (metadata.recoveryWindowEndLba ?: metadata.recoveryWindowStartLba ?: 0) - (metadata.recoveryWindowStartLba ?: 0)
                                    if (winSize > 0) {
                                        logger.record(RipLogEvent.TargetedSectorRecoveryLogged(
                                            trackNumber = trackNumber,
                                            lbaStart = metadata.recoveryWindowStartLba ?: currentChunk.startLba,
                                            sectorCount = winSize,
                                            strategy = metadata.strategy
                                        ))
                                    }
                                    if (metadata.driftEvent != null) {
                                        trackDriftEvents++
                                        logger.record(RipLogEvent.ReadDriftDetected(
                                            trackNumber = trackNumber,
                                            lbaStart = currentChunk.startLba,
                                            shiftSamples = metadata.driftEvent.shiftSamples,
                                            confidence = metadata.driftEvent.confidence.name
                                        ))
                                    }
                                    for (i in 0 until metadata.rereadAttempts) {
                                        trackEscalations++
                                        metricsCollector.recordRereadEscalation(config.chunkSize)
                                    }
                                    totalAttempts += metadata.rereadAttempts
                                    if (metadata.strategy == "overlap_recovery") {
                                        AppLogger.d("RipManager", "overlap_recovery_attempt track=$trackNumber recoveryWindowStartLba=${metadata.recoveryWindowStartLba} recoveryWindowEndLba=${metadata.recoveryWindowEndLba} rereadAttempts=${metadata.rereadAttempts} confidence=${if(metadata.recovered) "MEDIUM" else "LOW"}")
                                        if (metadata.recovered) {
                                            AppLogger.d("RipManager", "overlap_recovery_succeeded track=$trackNumber recoveryWindowStartLba=${metadata.recoveryWindowStartLba} recoveryWindowEndLba=${metadata.recoveryWindowEndLba} rereadAttempts=${metadata.rereadAttempts} confidence=MEDIUM")
                                        } else {
                                            AppLogger.w("RipManager", "overlap_recovery_failed track=$trackNumber recoveryWindowStartLba=${metadata.recoveryWindowStartLba} recoveryWindowEndLba=${metadata.recoveryWindowEndLba} rereadAttempts=${metadata.rereadAttempts} confidence=LOW")
                                        }
                                    } else if (metadata.strategy == "full_chunk_recovery") {
                                        AppLogger.w("RipManager", "escalation_to_full_reread track=$trackNumber recoveryWindowStartLba=${metadata.recoveryWindowStartLba} recoveryWindowEndLba=${metadata.recoveryWindowEndLba} rereadAttempts=${metadata.rereadAttempts} confidence=${if(metadata.recovered) "MEDIUM" else "LOW"}")
                                    }
                                }

                                if (finalMetadata != null) {
                                    if (finalMetadata.recovered) {
                                        logger.record(RipLogEvent.RecoverySucceeded(
                                            trackNumber = trackNumber,
                                            lbaStart = currentChunk.startLba,
                                            lbaEnd = currentChunk.endLba,
                                            rereadAttempts = totalAttempts
                                        ))
                                    } else {
                                        logger.record(RipLogEvent.RecoveryFailed(
                                            trackNumber = trackNumber,
                                            lbaStart = currentChunk.startLba,
                                            lbaEnd = currentChunk.endLba,
                                            rereadAttempts = totalAttempts
                                        ))
                                    }
                                }

                                val suspiciousRead = if (finalMetadata != null) {
                                    SuspiciousRead(
                                        startLba = currentChunk.startLba,
                                        endLba = currentChunk.endLba,
                                        recoveryWindowStartLba = finalMetadata.recoveryWindowStartLba,
                                        recoveryWindowEndLba = finalMetadata.recoveryWindowEndLba,
                                        strategy = finalMetadata.strategy,
                                        rereadAttempts = totalAttempts,
                                        recovered = finalMetadata.recovered,
                                        driftEvent = finalMetadata.driftEvent,
                                        cacheProbeResult = finalMetadata.cacheProbeResult
                                    )
                                } else {
                                    SuspiciousRead(
                                        startLba = currentChunk.startLba,
                                        endLba = currentChunk.endLba,
                                        recoveryWindowStartLba = null,
                                        recoveryWindowEndLba = null,
                                        strategy = null,
                                        rereadAttempts = 0,
                                        recovered = false,
                                        driftEvent = null,
                                        cacheProbeResult = null
                                    )
                                }

                                val comparisonHistory = when (recoveryResult) {
                                    is RereadRecoveryResult.Recovered -> recoveryResult.comparisonHistory
                                    is RereadRecoveryResult.Failed -> recoveryResult.comparisonHistory
                                    else -> null
                                }

                                if (comparisonHistory != null && comparisonHistory.uniqueCandidates > 1) {
                                    AppLogger.d("RipManager", "[US-003] Track $trackNumber overlap instability detected")
                                    AppLogger.d("RipManager", "[US-003] Candidate count=${comparisonHistory.totalAttempts} unique=${comparisonHistory.uniqueCandidates} type=${comparisonHistory.instabilityType.name}")
                                    if (comparisonHistory.stableCandidate != null) {
                                        AppLogger.d("RipManager", "[US-003] Stable candidate converged after attempt ${comparisonHistory.stableCandidate.firstSeenAttempt}")
                                    }
                                    logger.record(RipLogEvent.MultiPassComparisonCompleted(
                                        trackNumber = trackNumber,
                                        lbaStart = currentChunk.startLba,
                                        totalAttempts = comparisonHistory.totalAttempts,
                                        uniqueCandidates = comparisonHistory.uniqueCandidates,
                                        instabilityType = comparisonHistory.instabilityType.name,
                                        resolved = comparisonHistory.stableCandidate != null
                                    ))
                                }

                                currentChunkConfidence = confidenceEvaluator.evaluateChunkConfidence(
                                    overlapMatchedImmediately = false,
                                    rereadsPerformed = suspiciousRead.rereadAttempts,
                                    recoverySucceeded = suspiciousRead.recovered,
                                    driftEvent = suspiciousRead.driftEvent,
                                    instabilityType = comparisonHistory?.instabilityType
                                )

                                logger.record(RipLogEvent.ReadConsistencyScored(
                                    trackNumber = trackNumber,
                                    lbaStart = currentChunk.startLba,
                                    score = when (currentChunkConfidence) {
                                        RipConfidence.HIGH -> 1.0f
                                        RipConfidence.MEDIUM -> 0.5f
                                        RipConfidence.LOW -> 0.0f
                                        RipConfidence.DAMAGED -> 0.0f
                                    }
                                ))

                                currentChunk = when (recoveryResult) {
                                    is RereadRecoveryResult.Recovered -> recoveryResult.chunk
                                    is RereadRecoveryResult.Failed -> recoveryResult.chunk
                                    else -> currentChunk
                                }


                                currentSuspiciousRegions.add(suspiciousRead)

                                // Logging for driftEvent is handled directly in RereadEngine

                                onProgress(sectorsRead.toFloat() / effectiveTotalSectors, currentConfidence, currentSuspiciousRegions)

                                committedPcm = overlapVerifier.commitVerifiedAudio(pChunk, isFinal = false)

                                val trimmedSamples = (pChunk.pcm.size - committedPcm.size) / 4
                                totalOverlapTrimmedSamples += trimmedSamples
                                expectedTotalOverlapTrimmedSamples += overlapVerifier.overlapSizeBytes / 4
                            }
                        }

                        var trackConfidence = confidenceEvaluator.aggregateTrackConfidence(currentConfidence, currentChunkConfidence)

                        if (committedPcm != null && lastCommittedPcm != null) {
                            val alignmentResult = alignmentValidator.validateBoundary(
                                previousCommittedPcm = lastCommittedPcm,
                                nextCommittedPcm = committedPcm,
                                expectedTrimSamples = overlapVerifier.overlapSizeBytes / 4,
                                actualTrimSamples = (pendingChunk!!.pcm.size - committedPcm.size) / 4
                            )

                            val preAlignmentConfidence = trackConfidence
                            trackConfidence = confidenceEvaluator.evaluateAlignmentConfidence(trackConfidence, alignmentResult)

                            val fpWasEligible3 = fastPathEvaluator.state.eligible
                            if (trackConfidence != preAlignmentConfidence) {
                                fastPathEvaluator.reportConfidenceDowngrade()
                            }
                            trackAlignmentChecks++
                            if (alignmentResult.anomalies.isNotEmpty()) {
                                fastPathEvaluator.reportAnomaly()
                            }
                            if (fpWasEligible3 && !fastPathEvaluator.state.eligible) {
                                logger.record(RipLogEvent.FastPathStateChanged(trackNumber, false, "AlignmentAnomaly/Downgrade"))
                            }

                            if (alignmentResult.anomalies.isEmpty()) {
                                logger.record(RipLogEvent.SampleAlignmentValidated(
                                    trackNumber = trackNumber,
                                    valid = true
                                ))
                            }

                            for (anomaly in alignmentResult.anomalies) {
                                when (anomaly) {
                                    is AlignmentIssue.DuplicateSamples -> {
                                        AppLogger.w("RipManager", "[US-018] Duplicate samples detected")
                                        AppLogger.w("RipManager", "[US-018] SampleCount=${anomaly.sampleCount}")
                                        AppLogger.w("RipManager", "[US-018] BoundaryOffset=${anomaly.boundaryOffset}")
                                        logger.record(RipLogEvent.SampleAlignmentValidated(
                                            trackNumber = trackNumber,
                                            valid = false,
                                            anomalyType = "DuplicateSamples",
                                            sampleCount = anomaly.sampleCount
                                        ))
                                    }
                                    is AlignmentIssue.DroppedSamples -> {
                                        AppLogger.w("RipManager", "[US-018] Dropped samples detected")
                                        AppLogger.w("RipManager", "[US-018] SampleCount=${anomaly.sampleCount}")
                                        AppLogger.w("RipManager", "[US-018] BoundaryOffset=${anomaly.boundaryOffset}")
                                        logger.record(RipLogEvent.SampleAlignmentValidated(
                                            trackNumber = trackNumber,
                                            valid = false,
                                            anomalyType = "DroppedSamples",
                                            sampleCount = anomaly.sampleCount
                                        ))
                                    }
                                    is AlignmentIssue.InvalidOverlapTrim -> {
                                        AppLogger.w("RipManager", "[US-018] Invalid overlap trim")
                                        AppLogger.w("RipManager", "[US-018] ExpectedTrim=${anomaly.expectedTrimSamples}")
                                        AppLogger.w("RipManager", "[US-018] ActualTrim=${anomaly.actualTrimSamples}")
                                        logger.record(RipLogEvent.SampleAlignmentValidated(
                                            trackNumber = trackNumber,
                                            valid = false,
                                            anomalyType = "InvalidOverlapTrim",
                                            expectedTrim = anomaly.expectedTrimSamples,
                                            actualTrim = anomaly.actualTrimSamples
                                        ))
                                    }
                                    is AlignmentIssue.BoundaryDiscontinuity -> {
                                        AppLogger.w("RipManager", "[US-018] Boundary discontinuity")
                                        AppLogger.w("RipManager", "[US-018] MismatchOffset=${anomaly.mismatchSampleOffset}")
                                        logger.record(RipLogEvent.SampleAlignmentValidated(
                                            trackNumber = trackNumber,
                                            valid = false,
                                            anomalyType = "BoundaryDiscontinuity"
                                        ))
                                    }
                                }
                            }
                        }

                        if (trackConfidence != currentConfidence) {
                            currentConfidence = trackConfidence
                            onProgress(sectorsRead.toFloat() / effectiveTotalSectors, currentConfidence, currentSuspiciousRegions)
                        }

                        pendingChunk = currentChunk

                        if (committedPcm != null && committedPcm.isNotEmpty()) {
                            val trimmed = if (isFirstSector && config.skipBytes > 0) committedPcm.copyOfRange(config.skipBytes, committedPcm.size) else committedPcm
                            encoder.encode(trimmed)
                            checksumAccumulator.accumulate(trimmed)
                            analyser.feed(trimmed)
                            lastCommittedPcm = committedPcm
                            isFirstSector = false
                        }


                        val dynamicAdvance = currentChunk.pcm.size / 2352 - overlapVerifier.overlapSizeBytes / 2352
                        val isFinalChunk = (sectorsRead + dynamicAdvance) >= effectiveTotalSectors || currentChunk.pcm.size / 2352 < config.chunkSize

                        if (isFinalChunk) {
                            if (pendingChunk != null) {
                                val finalCommitted = overlapVerifier.commitVerifiedAudio(pendingChunk!!, isFinal = true)
                                if (finalCommitted.isNotEmpty()) {
                                    val trimmed = if (isFirstSector && config.skipBytes > 0) finalCommitted.copyOfRange(config.skipBytes, finalCommitted.size) else finalCommitted
                                    encoder.encode(trimmed)
                                    checksumAccumulator.accumulate(trimmed)
                                    analyser.feed(trimmed)
                                    lastCommittedPcm = finalCommitted
                                    isFirstSector = false
                                }
                                pendingChunk = null
                            }
                            sectorsRead += sectorsActuallyRead
                        } else {
                            advanceSize = currentChunk.pcm.size / 2352 - overlapVerifier.overlapSizeBytes / 2352
                            sectorsRead += advanceSize
                        }
                    } else {
                        metricsCollector.recordTransportFailure(config.chunkSize)
                        logger.record(RipLogEvent.TransportAnomaly(
                            trackNumber = trackNumber,
                            anomalyType = "TRANSPORT_FAILURE",
                            details = "Failed to read sector ${firstLba + sectorsRead} after 3 attempts " +
                                "(${sectorsRead}/${effectiveTotalSectors} sectors complete, " +
                                "${String.format("%.1f", sectorsRead * 100.0 / effectiveTotalSectors)}%)"
                        ))
                        if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                            return TrackRipResult.Failed("Disc removed or drive not ready during rip")
                        }

                        val newConfidence = confidenceEvaluator.aggregateTrackConfidence(currentConfidence, RipConfidence.DAMAGED)

                        val suspiciousRead = SuspiciousRead(
                            startLba = firstLba + sectorsRead,
                            endLba = firstLba + effectiveTotalSectors,
                            recoveryWindowStartLba = null,
                            recoveryWindowEndLba = null,
                            strategy = null,
                            rereadAttempts = 3, // UsbReadSession.MAX_RETRIES
                            recovered = false
                        )

                        currentSuspiciousRegions.add(suspiciousRead)

                        if (newConfidence != currentConfidence || currentSuspiciousRegions.size > 0) {
                            currentConfidence = newConfidence
                            onProgress(sectorsRead.toFloat() / effectiveTotalSectors, newConfidence, currentSuspiciousRegions)
                        }

                        throw java.io.IOException(
                            "Failed to read sector ${firstLba + sectorsRead} after 3 attempts " +
                            "(${sectorsRead}/${effectiveTotalSectors} sectors, track $trackNumber)"
                        ) // see UsbReadSession.MAX_RETRIES
                    }

                    onProgress(sectorsRead.toFloat() / effectiveTotalSectors, currentConfidence, currentSuspiciousRegions)
                }

                if (isCancelled()) {
                    return TrackRipResult.Cancelled
                }

                if (config.sampleOffset > 0) {
                    if (!isLastTrack) {
                        val overreadPcm = session.readSectors(lbaStart + totalSectors - toc.pregapOffset, 1)
                        if (overreadPcm == null) {
                            if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                                return TrackRipResult.Failed("Disc removed or drive not ready during rip")
                            }
                            throw java.io.IOException("Failed to read overshoot sector ${lbaStart + totalSectors - toc.pregapOffset} after 3 attempts") // see UsbReadSession.MAX_RETRIES
                        }
                        val toFeed = overreadPcm.copyOfRange(0, config.skipBytes)
                        encoder.encode(toFeed)
                        checksumAccumulator.accumulate(toFeed)
                        analyser.feed(toFeed)
                        overreadBuffer = overreadPcm.copyOfRange(config.skipBytes, overreadPcm.size)
                    } else {
                        val silence = ByteArray(config.skipBytes)
                        encoder.encode(silence)
                        checksumAccumulator.accumulate(silence)
                        analyser.feed(silence)
                        overreadBuffer = null
                    }
                } else {
                    overreadBuffer = null
                }

                if (isLastTrack && config.tocOffset > 0) {
                    val silence = ByteArray(config.tocOffset * 2352)
                    encoder.encode(silence)
                    checksumAccumulator.accumulate(silence)
                    analyser.feed(silence)
                }

                val finalTrackValidation = alignmentValidator.validateFinalTrack(
                    finalPcmSizeBytes = checksumAccumulator.getTotalProcessedBytes(),
                    expectedSamples = totalSamples,
                    totalOverlapTrimmedSamples = totalOverlapTrimmedSamples,
                    expectedTotalOverlapTrimmedSamples = expectedTotalOverlapTrimmedSamples
                )

                val finalConfidence = confidenceEvaluator.evaluateAlignmentConfidence(currentConfidence, finalTrackValidation)
                if (finalConfidence != currentConfidence) {
                    currentConfidence = finalConfidence
                    onProgress(sectorsRead.toFloat() / effectiveTotalSectors, finalConfidence, currentSuspiciousRegions)
                }



                val streamingResult = streamingBehaviorAnalyzer.analyze(
                    reads = streamingReads,
                    overlapFailures = trackOverlapFailures,
                    rereads = currentSuspiciousRegions.sumOf { it.rereadAttempts } ?: 0,
                    recoveryWindows = trackRecoveryWindows
                )
                if (streamingResult.classification != com.bitperfect.app.ripping.streaming.StreamingClassification.STABLE_STREAMING) {
                    AppLogger.d("StreamingBehaviorAnalyzer", "Sequential instability detected")
                    AppLogger.d("StreamingBehaviorAnalyzer", "window=$firstLba-$lastReadableLba")

                    AppLogger.d("StreamingBehaviorAnalyzer", "stallPercentage=${streamingResult.metrics.stallPercentage}")
                    AppLogger.d("StreamingBehaviorAnalyzer", "classification=${streamingResult.classification.name}")
                } else {
                    AppLogger.d("StreamingBehaviorAnalyzer", "Stable streaming observed")
                    AppLogger.d("StreamingBehaviorAnalyzer", "classification=${streamingResult.classification.name}")
                }

                for (anomaly in finalTrackValidation.anomalies) {
                    when (anomaly) {
                        is AlignmentIssue.InvalidOverlapTrim -> {
                            AppLogger.w("RipManager", "[US-018] Final Invalid overlap trim")
                            AppLogger.w("RipManager", "[US-018] ExpectedTrim=${anomaly.expectedTrimSamples}")
                            AppLogger.w("RipManager", "[US-018] ActualTrim=${anomaly.actualTrimSamples}")
                        }
                        is AlignmentIssue.BoundaryDiscontinuity -> {
                            AppLogger.w("RipManager", "[US-018] Final Boundary discontinuity")
                            AppLogger.w("RipManager", "[US-018] MismatchOffset=${anomaly.mismatchSampleOffset}")
                        }
                        else -> {}
                    }
                }

                encoder?.stop()
                tempOutputStream?.close() // Flush remaining buffers to temp file

                var audioAnalysis: AudioAnalysis? = null
                try {
                    audioAnalysis = analyser.analyse()
                } catch (e: Exception) {
                    AppLogger.w("RipManager", "Audio analysis failed for track $trackNumber: ${e.message}")
                }

                // Now build metadata with analysis
                val (metadataBytes, currentChecksumV1, currentChecksumV2) = run {
                    val currentPair = checksumAccumulator.finalise()
                    val cV1 = currentPair.first
                    val cV2 = currentPair.second

                    val currentActiveCandidates = activePressingCandidates.toMutableSet()
                    currentActiveCandidates.retainAll { pressing ->
                        val dbTrack = pressing.tracks[trackNumber] ?: return@retainAll false
                        if (dbTrack.crcV2 != null) {
                            dbTrack.crcV2 == cV2
                        } else {
                            dbTrack.crcV1 == cV1
                        }
                    }

                    val currentMatchedVersion = if (currentActiveCandidates.isNotEmpty()) {
                        if (currentActiveCandidates.any { it.tracks[trackNumber]?.crcV2 == cV2 }) 2 else 1
                    } else null

                    val currentMatchedConfidence = if (currentActiveCandidates.isNotEmpty()) {
                        currentActiveCandidates.mapNotNull { it.tracks[trackNumber]?.confidence }.maxOrNull()
                    } else null

                    val currentExpectedV1 = expectedChecksums.mapNotNull { it.tracks[trackNumber]?.crcV1 }.distinct()
                    val currentExpectedV2 = expectedChecksums.mapNotNull { it.tracks[trackNumber]?.crcV2 }.distinct()
                    val currentHasExpected = currentExpectedV1.isNotEmpty() || currentExpectedV2.isNotEmpty()

                    val currentStatus = if (currentActiveCandidates.isNotEmpty()) {
                        RipStatus.SUCCESS
                    } else if (!currentHasExpected) {
                        RipStatus.UNVERIFIED
                    } else {
                        RipStatus.WARNING
                    }

                    val currentStatusString = when {
                        currentStatus == RipStatus.SUCCESS -> buildString {
                            append("VERIFIED (v$currentMatchedVersion")
                            currentMatchedConfidence?.let { append(", confidence $it") }
                            append(")")
                        }
                        currentExpectedV1.isNotEmpty() || currentExpectedV2.isNotEmpty() -> "MISMATCH"
                        else -> "NOT_IN_DB"
                    }

                    val bytes = buildFlacMetadata(
                        totalSamples = totalSamples,
                        artist = normalizeMeta(metadata.artistName),
                        album = normalizeMeta(metadata.albumTitle),
                        title = normalizeMeta(trackTitle),
                        track = trackNumber,
                        year = metadata.year,
                        genre = metadata.genre?.let { normalizeMeta(it) },
                        albumArtist = metadata.albumArtist?.let { normalizeMeta(it) },
                        mbReleaseId = metadata.mbReleaseId,
                        accurateRipUrl = accurateRipUrl,
                        artworkBytes = artworkBytes,
                        plainLyrics = lyricsResult?.plainLyrics,
                        syncedLyrics = lyricsResult?.syncedLyrics,
                        discNumber = metadata.discNumber,
                        totalDiscs = metadata.totalDiscs,
                        releaseTags = metadata.releaseTags,
                        trackTags = metadata.trackTags.getOrNull(i) ?: emptyList(),
                        audioAnalysis = audioAnalysis,
                        computedChecksumV1 = cV1,
                        computedChecksumV2 = cV2,
                        accurateRipStatus = currentStatusString
                    )
                    Triple(bytes, cV1, cV2)
                }

                when (val writeResult = writeTrackFile(context, destFile, metadataBytes, tempFile)) {
                    is WriteTrackResult.Failed -> return TrackRipResult.Failed(writeResult.reason)
                    WriteTrackResult.Success -> { /* continue */ }
                }

                ripSucceeded = true
                val stats = TrackRipStats(
                    chunksRead = trackChunksRead,
                    overlapVerifications = trackOverlapVerifications,
                    overlapFailures = trackOverlapFailures,
                    alignmentChecks = trackAlignmentChecks,
                    driftEvents = trackDriftEvents,
                    recoveryWindows = trackRecoveryWindows,
                    escalations = trackEscalations,
                    fastPathChunks = trackFastPathChunks
                )

                return TrackRipResult.Success(
                    checksumV1 = currentChecksumV1,
                    checksumV2 = currentChecksumV2,
                    sectorsRead = sectorsRead,
                    missingStartSectors = missingStartSectors,
                    confidence = currentConfidence,
                    suspiciousRegions = currentSuspiciousRegions,
                    stats = stats,
                    overreadBuffer = overreadBuffer,
                    streamingReads = streamingReads
                )
            } catch (e: Exception) {
                AppLogger.e("RipManager", "Error ripping track $trackNumber", e)
                return TrackRipResult.Failed(e.message ?: "Unknown error")
            } finally {
                try {
                    encoder?.stop()
                } catch (e: Exception) {
                    AppLogger.w("RipManager", "Failed to safely stop encoder in finally block: ${e.message}")
                }
                try {
                    tempOutputStream?.close()
                } catch (e: Exception) {
                    AppLogger.w("RipManager", "Failed to close temp output stream in finally block: ${e.message}")
                }

                tempFile.delete()
            }

}


    suspend fun startRipping(session: UsbReadSession) = withContext(Dispatchers.IO) {
        val driveOffset: Int = try {
            DriveOffsetRepository(context).findOffset(driveVendor, driveProduct)?.offset ?: 0
        } catch (e: Exception) {
            AppLogger.w("RipManager", "Could not determine drive offset, defaulting to 0: ${e.message}")
            0
        }
        AppLogger.d("RipManager", "Drive offset: $driveOffset samples")

        val config = RipConfig.from(driveOffset)

        val logger = DefaultForensicRipLogger()
        val driveFirmware = DeviceStateManager.driveStatus.value.info?.firmware

        val nowIso = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_INSTANT)

        logger.record(
            RipLogEvent.SessionStarted(
                appVersion = "BitPerfect ${BuildConfig.VERSION_NAME}",
                deviceModel = android.os.Build.MODEL,
                androidVersion = "Android ${android.os.Build.VERSION.RELEASE}",
                timestampIso = nowIso,
                mode = RipMode.SECURE,
                chunkSize = config.chunkSize,
                overlapSize = config.overlapSize,
                driveVendor = driveVendor,
                driveModel = driveProduct,
                driveFirmware = driveFirmware,
                albumTitle = metadata.albumTitle,
                artistName = metadata.artistName
            )
        )

        val dirs = try {
            setupOutputDirectory(
                context,
                outputFolderUriString,
                metadata.artistName,
                metadata.albumTitle
            )
        } catch (e: java.io.IOException) {
            AppLogger.e("RipManager", "Failed to set up output directory: ${e.message}")
            return@withContext
        }

        val artistDir = dirs.artistDir
        albumDir = dirs.albumDir

        val metricsCollector = ReadSizeMetricsCollector()
        val allStreamingReads = mutableListOf<com.bitperfect.app.ripping.streaming.SequentialReadTelemetry>()

        launch {
            try {
                val existingFile = artistDir.findFile("artist.json")
                if (existingFile == null) {
                    val responseBody = audioDbRepository.fetchArtist(metadata.artistName)
                    if (responseBody != null) {
                        val file = artistDir.createFile("application/json", "artist.json")
                        if (file != null) {
                            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                                out.write(responseBody.toByteArray(Charsets.UTF_8))
                            }
                            AppLogger.d("RipManager", "Successfully fetched and saved artist.json")
                        } else {
                            AppLogger.e("RipManager", "Could not create artist.json file")
                        }
                    } else {
                        AppLogger.w("RipManager", "Failed to fetch artist.json from AudioDB")
                    }
                } else {
                    AppLogger.d("RipManager", "artist.json already exists, skipping fetch")
                }
            } catch (e: Exception) {
                AppLogger.w("RipManager", "Failed to fetch artist.json: ${e.message}")
            }
        }


        val accurateRipUrl = AccurateRipService().getAccurateRipUrl(toc)

        var overreadBuffer: ByteArray? = null

        while (trackQueue.isNotEmpty()) {
            if (isCancelled) break

            val trackNumber = trackQueue.poll() ?: break

            val trackStartTimeMs = android.os.SystemClock.elapsedRealtime()

            val i = trackNumber - 1
            if (i < 0 || i >= toc.tracks.size) continue

            val entry = toc.tracks[i]
            val trackTitle = metadata.trackTitles.getOrNull(i) ?: "Track $trackNumber"
            val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.effectiveAudioLeadOutLba
            val totalSectors = nextLba - entry.lba
            val totalSamples = totalSectors.toLong() * 588L

            updateTrackState(trackNumber, RipStatus.RIPPING, 0f, extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0)
            logger.record(RipLogEvent.TrackStarted(trackNumber, trackTitle))

            if (isCancelled) break

            val lyricsResult = (lyricsMap[trackNumber] as? LyricsFetchResult.Success)?.lyrics

            val safeTitle = trackTitle.replace("/", "_")

            val totalDiscs = metadata.totalDiscs
            val discNumber = metadata.discNumber
            val filename = if (totalDiscs != null && totalDiscs > 1 && discNumber != null) {
                String.format("%d-%02d %s.flac", discNumber, trackNumber, safeTitle)
            } else {
                String.format("%02d - %s.flac", trackNumber, safeTitle)
            }

            albumDir?.findFile(filename)?.delete()
            val destFile = albumDir?.createFile("audio/flac", filename)
            if (destFile == null) {
                AppLogger.e("RipManager", "Failed to create SAF destination for track $trackNumber")
                updateTrackState(
                    trackNumber = trackNumber,
                    status = RipStatus.ERROR,
                    progress = 0f,
                    errorMessage = "Failed to create destination file",
                    startLba = entry.lba,
                    endLba = nextLba,
                    totalSectors = totalSectors,
                    sectorsRead = 0,
                    totalSamples = totalSamples,
                    durationSeconds = 0.0,
                    extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0
                )
                continue
            }


            val ripResult = ripTrack(
                context = context,
                trackNumber = trackNumber,
                i = i,
                entry = entry,
                nextLba = nextLba,
                totalSectors = totalSectors,
                totalSamples = totalSamples,
                trackTitle = trackTitle,
                lyricsResult = lyricsResult,
                destFile = destFile,
                config = config,
                toc = toc,
                metadata = metadata,
                accurateRipUrl = accurateRipUrl,
                artworkBytes = artworkBytes,
                expectedChecksums = expectedChecksums,
                activePressingCandidates = activePressingCandidates.toSet(),
                session = session,
                metricsCollector = metricsCollector,
                logger = logger,
                incomingOverreadBuffer = overreadBuffer,
                isCancelled = { isCancelled },
                trackStartTimeMs = trackStartTimeMs,
                onProgress = { fraction, confidence, suspiciousRegions ->
                    updateTrackState(
                        trackNumber = trackNumber,
                        status = RipStatus.RIPPING,
                        progress = fraction,
                        extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0,
                        confidence = confidence,
                        suspiciousRegions = suspiciousRegions
                    )
                }
            )

            var finalChecksumV1 = 0L
            var finalChecksumV2 = 0L

            when (ripResult) {
                is TrackRipResult.Cancelled -> break
                is TrackRipResult.Failed -> {
                    updateTrackState(
                        trackNumber = trackNumber,
                        status = RipStatus.ERROR,
                        progress = 0f,
                        errorMessage = ripResult.reason,
                        startLba = entry.lba,
                        endLba = nextLba,
                        totalSectors = totalSectors,
                        sectorsRead = 0,
                        totalSamples = totalSamples,
                        durationSeconds = 0.0,
                        extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0
                    )
                    continue
                }
                is TrackRipResult.Success -> {
                    overreadBuffer = ripResult.overreadBuffer
                    finalChecksumV1 = ripResult.checksumV1
                    finalChecksumV2 = ripResult.checksumV2
                    val sectorsRead = ripResult.sectorsRead
                    val missingStartSectors = ripResult.missingStartSectors
                    allStreamingReads.addAll(ripResult.streamingReads)

                    updateTrackState(
                        trackNumber = trackNumber,
                        status = RipStatus.RIPPING,
                        progress = sectorsRead.toFloat() / totalSectors,
                        startLba = entry.lba,
                        endLba = nextLba,
                        totalSectors = totalSectors,
                        sectorsRead = sectorsRead + missingStartSectors,
                        totalSamples = totalSamples,
                        durationSeconds = (sectorsRead + missingStartSectors).toLong() * 588L / 44100.0,
                        extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0,
                        confidence = ripResult.confidence,
                        suspiciousRegions = ripResult.suspiciousRegions
                    )
                }
            }
            MediaScannerHelper.scanSafUri(context, destFile.uri)

            updateTrackState(trackNumber, RipStatus.VERIFYING, 1f, extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0)

            val verification = verifyTrack(
                trackNumber = trackNumber,
                checksumV1 = finalChecksumV1,
                checksumV2 = finalChecksumV2,
                activePressingCandidates = activePressingCandidates,
                expectedChecksums = expectedChecksums
            )

            updateTrackState(
                trackNumber = trackNumber,
                status = verification.finalStatus,
                progress = 1f,
                accurateRipUrl = accurateRipUrl,
                computedChecksumV1 = finalChecksumV1,
                computedChecksumV2 = finalChecksumV2,
                expectedChecksumsV1 = verification.allExpectedV1,
                expectedChecksumsV2 = verification.allExpectedV2,
                matchedVersion = verification.matchedVersion,
                arConfidence = verification.matchedConfidence,
                extractionTimeSeconds = (android.os.SystemClock.elapsedRealtime() - trackStartTimeMs) / 1000.0
            )

            val currentState = _trackStates.value[trackNumber]
            if (currentState != null) {
                writeAccurateRipJsonl(albumDir, currentState)

                val accurateRipStatusString = when {
                    currentState.status == RipStatus.SUCCESS -> buildString {
                        append("VERIFIED (v${currentState.matchedVersion}")
                        currentState.arConfidence?.let { append(", confidence $it") }
                        append(")")
                    }
                    currentState.expectedChecksumsV1.isNotEmpty() || currentState.expectedChecksumsV2.isNotEmpty() -> "MISMATCH"
                    else -> "NOT_IN_DB"
                }

                logger.record(RipLogEvent.TrackCompleted(
                    trackNumber = trackNumber,
                    confidence = currentState.confidence,
                    rereads = currentState.suspiciousRegions.sumOf { it.rereadAttempts },
                    suspiciousReads = currentState.suspiciousRegions.size,
                    status = currentState.status,
                    accurateRipStatus = accurateRipStatusString,
                    arConfidence = currentState.arConfidence,
                    computedChecksumV1 = currentState.computedChecksumV1,
                    computedChecksumV2 = currentState.computedChecksumV2,
                    expectedChecksumsV1 = currentState.expectedChecksumsV1,
                    expectedChecksumsV2 = currentState.expectedChecksumsV2,
                    startLba = currentState.startLba,
                    endLba = currentState.endLba,
                    totalSectors = currentState.totalSectors,
                    sectorsRead = currentState.sectorsRead,
                    durationSeconds = currentState.durationSeconds,
                    extractionTimeSeconds = currentState.extractionTimeSeconds,
                    summary = com.bitperfect.app.ripping.logging.RipLogEvent.TrackRipSummary(
                        chunksRead = if (ripResult is TrackRipResult.Success) ripResult.stats.chunksRead else 0,
                        overlapVerifications = if (ripResult is TrackRipResult.Success) ripResult.stats.overlapVerifications else 0,
                        overlapFailures = if (ripResult is TrackRipResult.Success) ripResult.stats.overlapFailures else 0,
                        alignmentChecks = if (ripResult is TrackRipResult.Success) ripResult.stats.alignmentChecks else 0,
                        driftEvents = if (ripResult is TrackRipResult.Success) ripResult.stats.driftEvents else 0,
                        recoveryWindows = if (ripResult is TrackRipResult.Success) ripResult.stats.recoveryWindows else 0,
                        escalations = if (ripResult is TrackRipResult.Success) ripResult.stats.escalations else 0,
                        fastPathChunks = if (ripResult is TrackRipResult.Success) ripResult.stats.fastPathChunks else 0
                    )
                ))
            }
        }

        if (!isCancelled) {
            val profiler = DefaultAtomicReadProfiler()
            val profile = profiler.analyze(metricsCollector.build())
            AppLogger.d("RipManager", "[AtomicReadProfiler] Final Profile: preferredReadSize=${profile.preferredReadSize} maxReliableReadSize=${profile.maxReliableReadSize} unstableSizes=${profile.unstableSizes}")

            val cacheProbeResult = _trackStates.value.values
                .flatMap { it.suspiciousRegions }
                .mapNotNull { it.cacheProbeResult }
                .lastOrNull()

            val streamingBehaviorAnalyzer = com.bitperfect.app.ripping.streaming.DefaultStreamingBehaviorAnalyzer()
            val totalRereads = _trackStates.value.values.sumOf { state -> state.suspiciousRegions.sumOf { it.rereadAttempts } }
            val globalStreamingResult = streamingBehaviorAnalyzer.analyze(
                reads = allStreamingReads,
                overlapFailures = 0,
                rereads = totalRereads,
                recoveryWindows = 0
            )

            val driveProfiler = com.bitperfect.app.ripping.capability.DefaultDriveProfiler()
            val driveInfo = DeviceStateManager.driveStatus.value.info ?: com.bitperfect.app.usb.DriveInfo(
                vendor = driveVendor,
                model = driveProduct,
                firmware = null,
                isOptical = true
            )

            val finalProfile = driveProfiler.buildProfile(
                driveInfo = driveInfo,
                cacheProbeResult = cacheProbeResult,
                streamingAnalysisResult = globalStreamingResult,
                readSizeProfile = profile
            )

            logger.record(RipLogEvent.DriveAnalysisCompleted(
                profile = finalProfile,
                cacheProbeResult = cacheProbeResult,
                streamingAnalysisResult = globalStreamingResult,
                readSizeProfile = profile
            ))

            val matchedPressing = activePressingCandidates.firstOrNull()
            logger.record(RipLogEvent.SessionCompleted(
                success = true,
                matchedDiscId1 = matchedPressing?.discId1,
                matchedDiscId2 = matchedPressing?.discId2
            ))

            albumDir?.let { dir ->
                logger.finalize(context, dir)
            }
        }
        // Eject drive upon successful completion if not cancelled
        if (!isCancelled) {
            val allSuccessful = _trackStates.value.values.all {
                it.status == RipStatus.SUCCESS || it.status == RipStatus.UNVERIFIED || it.status == RipStatus.WARNING
            }
            if (allSuccessful) {
                DeviceStateManager.ejectDrive()
            }
        }
    }

    private fun normalizeMeta(input: String): String =
        input
            .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u0060', '\'')
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
            .replace('\u2013', '-').replace('\u2014', '-')
            .trim()

    private fun writeAccurateRipJsonl(albumDir: DocumentFile?, state: TrackRipState) {
        val dir = albumDir ?: return
        try {
            var file = dir.findFile("BitPerfect.jsonl")
            val existingLines = mutableListOf<JSONObject>()

            if (file != null) {
                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.forEachLine { line ->
                            if (line.isNotBlank()) {
                                try {
                                    existingLines.add(JSONObject(line))
                                } catch (e: Exception) {
                                    // Ignore malformed lines
                                }
                            }
                        }
                    }
                }
            } else {
                file = dir.createFile("application/jsonl", "BitPerfect.jsonl")
            }

            if (file == null) return

            val isVerified = state.status == RipStatus.SUCCESS
            val checksumMatched = state.status == RipStatus.SUCCESS
            val inDatabase = state.expectedChecksumsV1.isNotEmpty() || state.expectedChecksumsV2.isNotEmpty() || state.status == RipStatus.SUCCESS

            // Remove existing entry for the same disc and track
            existingLines.removeAll {
                it.optInt("disc", -1) == state.discNumber && it.optInt("track", -1) == state.trackNumber
            }

            val newEntry = JSONObject()
            newEntry.put("disc", state.discNumber)
            newEntry.put("track", state.trackNumber)

            val accurateRipObj = JSONObject()
            accurateRipObj.put("isVerified", isVerified)
            accurateRipObj.put("checksumMatched", checksumMatched)
            accurateRipObj.put("matchedVersion", state.matchedVersion ?: JSONObject.NULL)
            accurateRipObj.put("confidence", state.arConfidence ?: JSONObject.NULL)
            accurateRipObj.put("inDatabase", inDatabase)

            if (state.computedChecksumV1 != null) {
                accurateRipObj.put("checksumV1", String.format("0x%08X", state.computedChecksumV1 and 0xFFFFFFFFL))
            }
            if (state.computedChecksumV2 != null) {
                accurateRipObj.put("checksumV2", String.format("0x%08X", state.computedChecksumV2 and 0xFFFFFFFFL))
            }
            newEntry.put("accurateRip", accurateRipObj)

            existingLines.add(newEntry)

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
                existingLines.forEach { obj ->
                    outputStream.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            MediaScannerHelper.scanSafUri(context, file.uri)
        } catch (e: Exception) {
            AppLogger.e("RipManager", "Failed to write BitPerfect.jsonl", e)
        }
    }

    private fun updateTrackState(
        trackNumber: Int,
        status: RipStatus,
        progress: Float,
        accurateRipUrl: String? = null,
        computedChecksumV1: Long? = null,
        computedChecksumV2: Long? = null,
        expectedChecksumsV1: List<Long> = emptyList(),
        expectedChecksumsV2: List<Long> = emptyList(),
        matchedVersion: Int? = null,
        arConfidence: Int? = null,
        errorMessage: String? = null,
        startLba: Int? = null,
        endLba: Int? = null,
        totalSectors: Int? = null,
        sectorsRead: Int? = null,
        totalSamples: Long? = null,
        durationSeconds: Double? = null,
        extractionTimeSeconds: Double? = null,
        confidence: RipConfidence? = null,
        suspiciousRegions: List<com.bitperfect.app.ripping.paranoia.SuspiciousRead>? = null
    ) {
        val currentStates = _trackStates.value.toMutableMap()
        val existingState = currentStates[trackNumber] ?: TrackRipState(trackNumber = trackNumber, discNumber = metadata.discNumber ?: 1)

        currentStates[trackNumber] = existingState.copy(
            progress = progress,
            status = status,
            accurateRipUrl = accurateRipUrl ?: existingState.accurateRipUrl,
            computedChecksumV1 = computedChecksumV1 ?: existingState.computedChecksumV1,
            computedChecksumV2 = computedChecksumV2 ?: existingState.computedChecksumV2,
            expectedChecksumsV1 = if (expectedChecksumsV1.isNotEmpty()) expectedChecksumsV1 else existingState.expectedChecksumsV1,
            expectedChecksumsV2 = if (expectedChecksumsV2.isNotEmpty()) expectedChecksumsV2 else existingState.expectedChecksumsV2,
            matchedVersion = matchedVersion ?: existingState.matchedVersion,
            arConfidence = arConfidence ?: existingState.arConfidence,
            errorMessage = errorMessage ?: existingState.errorMessage,
            startLba = startLba ?: existingState.startLba,
            endLba = endLba ?: existingState.endLba,
            totalSectors = totalSectors ?: existingState.totalSectors,
            sectorsRead = sectorsRead ?: existingState.sectorsRead,
            totalSamples = totalSamples ?: existingState.totalSamples,
            durationSeconds = durationSeconds ?: existingState.durationSeconds,
            extractionTimeSeconds = extractionTimeSeconds ?: existingState.extractionTimeSeconds,
            confidence = confidence ?: existingState.confidence,
            suspiciousRegions = suspiciousRegions ?: existingState.suspiciousRegions
        )
        _trackStates.value = currentStates
    }

    fun cancel() {
        isCancelled = true
    }

    fun deleteRipFiles() {
        try {
            albumDir?.delete()
        } catch (e: Exception) {
            AppLogger.e("RipManager", "Failed to delete album directory", e)
        }
    }


    private fun buildFlacMetadata(
        totalSamples: Long,
        artist: String?,
        album: String?,
        title: String?,
        track: Int,
        year: String?,
        genre: String?,
        albumArtist: String?,
        mbReleaseId: String?,
        accurateRipUrl: String?,
        artworkBytes: ByteArray?,
        plainLyrics: String?,
        syncedLyrics: String?,
        discNumber: Int?,
        totalDiscs: Int?,
        releaseTags: List<String> = emptyList(),
        trackTags: List<String> = emptyList(),
        audioAnalysis: AudioAnalysis? = null,
        computedChecksumV1: Long? = null,
        computedChecksumV2: Long? = null,
        accurateRipStatus: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // fLaC
        out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))

        // STREAMINFO (type 0, length 34 = 0x22)
        val hasPicture = artworkBytes != null
        out.write(0x00) // Type 0, last = false
        out.write(byteArrayOf(0x00, 0x00, 0x22))

        val streamInfo = java.nio.ByteBuffer.allocate(34)
        streamInfo.putShort(4096) // min block size
        streamInfo.putShort(4096) // max block size
        streamInfo.put(byteArrayOf(0, 0, 0)) // min frame size
        streamInfo.put(byteArrayOf(0, 0, 0)) // max frame size

        // sample rate 44100 (20 bits), channels 2 (3 bits), bps 16 (5 bits), total samples (36 bits)
        val sr = 44100
        val ch = 2 - 1
        val bps = 16 - 1

        val b1 = (sr shr 12) and 0xFF
        val b2 = (sr shr 4) and 0xFF
        val b3 = ((sr and 0xF) shl 4) or (ch shl 1) or ((bps shr 4) and 0x1)
        val b4 = ((bps and 0xF) shl 4) or ((totalSamples shr 32).toInt() and 0xF)
        val b5 = (totalSamples shr 24).toInt() and 0xFF
        val b6 = (totalSamples shr 16).toInt() and 0xFF
        val b7 = (totalSamples shr 8).toInt() and 0xFF
        val b8 = totalSamples.toInt() and 0xFF

        streamInfo.put(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte(), b5.toByte(), b6.toByte(), b7.toByte(), b8.toByte()))
        streamInfo.put(ByteArray(16)) // MD5 zeroed
        out.write(streamInfo.array())

        // VORBIS_COMMENT (type 4)
        val isLast = !hasPicture
        val vcType = if (isLast) 0x84 else 0x04

        val vcPayload = ByteArrayOutputStream()
        val vendorString = "BitPerfect".toByteArray(Charsets.UTF_8)
        vcPayload.writeLittleEndianInt(vendorString.size)
        vcPayload.write(vendorString)

        val comments = mutableListOf<String>()
        if (artist != null) comments.add("ARTIST=$artist")
        if (album != null) comments.add("ALBUM=$album")
        if (title != null) comments.add("TITLE=$title")
        comments.add("TRACKNUMBER=$track")
        if (discNumber != null) comments.add("DISCNUMBER=$discNumber")
        if (totalDiscs != null) comments.add("DISCTOTAL=$totalDiscs")
        if (year != null) comments.add("DATE=$year")
        if (genre != null) comments.add("GENRE=$genre")

        for (tag in releaseTags) {
            comments.add("STYLE=$tag")
        }
        for (tag in trackTags) {
            if (!releaseTags.contains(tag)) {
                comments.add("STYLE=$tag")
            }
        }

        if (albumArtist != null) comments.add("ALBUMARTIST=$albumArtist")
        if (mbReleaseId != null) comments.add("MUSICBRAINZ_ALBUMID=$mbReleaseId")
        if (accurateRipUrl != null) {
            // URL format: http://.../dBAR-010-000bba6a-006fbb59-71089d0a.bin
            // We want the IDs part: 010-000bba6a-006fbb59-71089d0a
            val regex = "dBAR-([^.]+)\\.bin".toRegex()
            val match = regex.find(accurateRipUrl)
            if (match != null) {
                comments.add("ACCURATERIPDISCID=${match.groupValues[1]}")
            }
        }
        if (computedChecksumV1 != null) {
            comments.add("ACCURATERIP_V1=${String.format("%08X", computedChecksumV1 and 0xFFFFFFFFL)}")
        }
        if (computedChecksumV2 != null) {
            comments.add("ACCURATERIP_V2=${String.format("%08X", computedChecksumV2 and 0xFFFFFFFFL)}")
        }
        if (accurateRipStatus != null) {
            comments.add("ACCURATERIPRESULT=$accurateRipStatus")
        }

        if (plainLyrics != null) comments.add("LYRICS=$plainLyrics")
        if (syncedLyrics != null) comments.add("SYNCEDLYRICS=$syncedLyrics")
        comments.add("BITDEPTH=16")
        comments.add("SAMPLERATE=44100")
        comments.add("COMMENT=Ripped with BitPerfect")

        audioAnalysis?.let { a ->
            if (a.bpm > 0f) comments.add("BPM=${String.format("%.1f", a.bpm)}")
            comments.add("INITIALKEY=${a.initialKey}")
            comments.add("REPLAYGAIN_TRACK_GAIN=${String.format("%.2f dB", a.replayGainDb)}")
            comments.add("REPLAYGAIN_TRACK_PEAK=${String.format("%.4f", a.replayGainPeak)}")
            comments.add("ENERGY=${String.format("%.3f", a.energy)}")
        }

        vcPayload.writeLittleEndianInt(comments.size)
        for (comment in comments) {
            val bytes = comment.toByteArray(Charsets.UTF_8)
            vcPayload.writeLittleEndianInt(bytes.size)
            vcPayload.write(bytes)
        }

        val vcBytes = vcPayload.toByteArray()
        out.write(vcType)
        out.write(byteArrayOf((vcBytes.size shr 16).toByte(), (vcBytes.size shr 8).toByte(), vcBytes.size.toByte()))
        out.write(vcBytes)

        if (hasPicture) {
            val picType = 0x86 // Last block = true, type 6
            val mimeType = detectMimeType(artworkBytes!!)
            val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
            val picPayload = ByteArrayOutputStream()

            picPayload.writeBigEndianInt(3) // Picture type: Front cover
            picPayload.writeBigEndianInt(mimeBytes.size)
            picPayload.write(mimeBytes)
            picPayload.writeBigEndianInt(0) // Description length
            picPayload.writeBigEndianInt(0) // width
            picPayload.writeBigEndianInt(0) // height
            picPayload.writeBigEndianInt(0) // color depth
            picPayload.writeBigEndianInt(0) // indexed colors
            picPayload.writeBigEndianInt(artworkBytes.size)
            picPayload.write(artworkBytes)

            val picBytes = picPayload.toByteArray()
            out.write(picType)
            out.write(byteArrayOf((picBytes.size shr 16).toByte(), (picBytes.size shr 8).toByte(), picBytes.size.toByte()))
            out.write(picBytes)
        }

        return out.toByteArray()
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        return "image/jpeg"
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBigEndianInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }


}
