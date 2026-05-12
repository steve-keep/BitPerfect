package com.bitperfect.app.ui.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.core.services.AccurateRipService
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.random.Random

class OffsetCalibrationViewModel(
    private val accurateRipService: AccurateRipService = AccurateRipService(),
    private val driveOffsetRepository: DriveOffsetRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OffsetCalibrationUiState())
    val uiState: StateFlow<OffsetCalibrationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DeviceStateManager.driveStatus.collect { driveStatus ->
                handleDriveStatus(driveStatus)
            }
        }
    }

    private fun handleDriveStatus(driveStatus: DriveStatus) {
        val state = _uiState.value
        val activeStepIndex = state.activeStepIndex

        // If we have already finished all steps or this step is not WaitingForDisc, do nothing automatically.
        if (activeStepIndex >= 3 || state.steps[activeStepIndex] !is CalibrationStepState.WaitingForDisc) {
            return
        }

        if (driveStatus is DriveStatus.DiscReady) {
            val toc = driveStatus.toc
            if (toc == null) {
                AppLogger.w("OffsetCalibration", "DiscReady but TOC is null")
                return
            }

            updateStepState(activeStepIndex, CalibrationStepState.CheckingDisc)

            viewModelScope.launch {
                var attemptedUrl: String? = null
                try {
                    attemptedUrl = accurateRipService.getAccurateRipUrl(toc)
                    val isKeyDisc = accurateRipService.checkIsKeyDisc(toc)
                    // We don't have discTitle extraction from MusicBrainz here directly without the repo,
                    // so we pass null or attempt to fetch it if we had MusicBrainzRepository.
                    // For now, null is acceptable.
                    if (isKeyDisc) {
                        updateStepState(activeStepIndex, CalibrationStepState.KeyDiscConfirmed(null))
                        viewModelScope.launch {
                            delay(2000)
                            startScan(activeStepIndex)
                        }
                    } else {
                        updateStepState(activeStepIndex, CalibrationStepState.NotAKeyDisc(null, attemptedUrl))
                    }
                } catch (e: Exception) {
                    updateStepState(activeStepIndex, CalibrationStepState.Error(e.message ?: "Unknown error", attemptedUrl))
                }
            }
        }
    }

    private fun updateStepState(index: Int, newState: CalibrationStepState) {
        _uiState.update { current ->
            val newSteps = current.steps.toMutableList()
            if (index in newSteps.indices) {
                newSteps[index] = newState
            }
            current.copy(steps = newSteps)
        }
    }

    private fun appendStepReport(report: CalibrationStepReport) {
        _uiState.update { current ->
            current.copy(sessionReport = current.sessionReport + report)
        }
    }

    fun startScan(stepIndex: Int) {
        val state = _uiState.value
        if (state.steps.getOrNull(stepIndex) !is CalibrationStepState.KeyDiscConfirmed) {
            return
        }

        updateStepState(stepIndex, CalibrationStepState.Scanning(0f, "Preparing..."))

        viewModelScope.launch {
            try {
                val driveStatus = DeviceStateManager.driveStatus.value as? DriveStatus.DiscReady
                    ?: throw IllegalStateException("Drive not ready")
                val toc = driveStatus.toc ?: throw IllegalStateException("TOC not available")

                // Prefer Track 2: it has no isFirstTrack exclusion window and its native LBA
                // is well above 0, giving full ±MAX_OFFSET_SAMPLES headroom in both directions.
                // Fall back to Track 1 only if the disc has fewer than 2 tracks.
                val useTrack2 = toc.tracks.size >= 2
                val trackIndex = if (useTrack2) 1 else 0   // 0-based index into toc.tracks
                val arTrackNumber = trackIndex + 1   // AccurateRip track numbers are 1-based
                var expectedChecksums: List<com.bitperfect.core.services.AccurateRipTrackMetadata>? = null
                var allChecksums: Map<Int, List<com.bitperfect.core.services.AccurateRipTrackMetadata>> = emptyMap()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    allChecksums = accurateRipService.getExpectedChecksums(toc)
                    expectedChecksums = allChecksums[arTrackNumber]
                }

                // If Track 2 has no AR entry for this pressing, fall back to Track 1.
                // This can happen on discs where only the first track was submitted.
                val (resolvedTrackIndex, resolvedArTrackNumber) = if (expectedChecksums == null && useTrack2) {
                    expectedChecksums = allChecksums[1]
                    Pair(0, 1)
                } else {
                    Pair(trackIndex, arTrackNumber)
                }

                if (expectedChecksums == null) {
                    throw IllegalStateException("No AccurateRip checksums found for track ${resolvedArTrackNumber}")
                }

                // Strip placeholder entries (checksum=0, confidence=0) that AccurateRip inserts for
                // unverified submissions. Matching against these produces false positives because the
                // inter-track gap before the scan window is silence, which also checksums to 0x00000000.
                val validChecksums = expectedChecksums!!.filter { it.checksum != 0L && it.confidence > 0 }
                if (validChecksums.isEmpty()) {
                    throw IllegalStateException(
                        "No valid AccurateRip checksums for track $resolvedArTrackNumber " +
                        "(all ${expectedChecksums!!.size} entries are zero-confidence placeholders)"
                    )
                }

                val resolvedTrack = toc.tracks[resolvedTrackIndex]
                val resolvedNextLba = if (resolvedTrackIndex + 1 < toc.tracks.size)
                    toc.tracks[resolvedTrackIndex + 1].lba else toc.leadOutLba
                val totalSectors = resolvedNextLba - resolvedTrack.lba
                val totalSamples = totalSectors.toLong() * 588L
                val nativeTrackStart = resolvedTrack.lba   // normalised LBA (pregap-adjusted, 150-based)

                val MAX_OFFSET_SAMPLES  = 3000
                val MAX_OFFSET_SECTORS  = (MAX_OFFSET_SAMPLES + 587) / 588   // = 6, ceiling division
                val sectorsToRead       = MAX_OFFSET_SECTORS + totalSectors + MAX_OFFSET_SECTORS

                // Compute headroom in normalised space — this is purely about how many pre-track
                // sectors exist on the disc, independent of how the drive numbers its LBAs.
                val normalisedReadStart = maxOf(0, nativeTrackStart - MAX_OFFSET_SECTORS)
                val actualPreSectors    = nativeTrackStart - normalisedReadStart   // 0..MAX_OFFSET_SECTORS

                // Convert to the physical LBA the drive expects, the same way RipManager does.
                // pregapOffset is subtracted to undo the normalisation stored in DiscToc.
                // On a 150-based drive pregapOffset=0 (no-op). On a 0-based drive pregapOffset=150.
                val readStartLba = normalisedReadStart - toc.pregapOffset

                val rawBuffer = java.io.ByteArrayOutputStream(sectorsToRead * 2352)
                var sectorsRead = 0
                val chunkSize = 8

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.bitperfect.app.usb.UsbReadSession.open().use { session ->
                        while (sectorsRead < sectorsToRead) {
                            val chunk   = minOf(chunkSize, sectorsToRead - sectorsRead)
                            val pcmData = session.readSectors(readStartLba + sectorsRead, chunk)
                                ?: throw java.io.IOException("Failed to read audio data")
                            rawBuffer.write(pcmData)
                            sectorsRead += (pcmData.size / 2352)
                            updateStepState(stepIndex, CalibrationStepState.Scanning(
                                sectorsRead.toFloat() / sectorsToRead, "Reading audio data..."))
                        }
                    }
                }

                val fullPcm = rawBuffer.toByteArray()
                val verifier = com.bitperfect.core.services.AccurateRipVerifier()

                val sampledChecksums = mutableListOf<String>()
                val matchingOffsets = mutableSetOf<Int>()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val trackBuffer = ByteArray(totalSectors * 2352)
                    var lastUpdate = System.currentTimeMillis()

                    for (offset in -MAX_OFFSET_SAMPLES..MAX_OFFSET_SAMPLES) {
                        if (!isActive) break

                        val requiredPreSectors = (-offset + 587) / 588
                        if (offset < 0 && requiredPreSectors > actualPreSectors) continue

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) {
                            updateStepState(stepIndex, CalibrationStepState.Scanning(
                                (offset + MAX_OFFSET_SAMPLES) / (2f * MAX_OFFSET_SAMPLES),
                                "Verifying offset $offset..."))
                            lastUpdate = now
                        }

                        val startByte = actualPreSectors * 2352 + offset * 4
                        if (startByte < 0 || startByte + trackBuffer.size > fullPcm.size) continue

                        System.arraycopy(fullPcm, startByte, trackBuffer, 0, trackBuffer.size)

                        val result = verifier.computeChecksumChunk(
                            pcmData        = trackBuffer,
                            samplePosition = 1L,
                            totalSamples   = totalSamples,
                            isFirstTrack   = (resolvedTrackIndex == 0),
                            isLastTrack    = (resolvedTrackIndex == toc.tracks.size - 1)
                        )
                        val checksum = verifier.finaliseChecksum(result.partialChecksum)

                        // Sample every 100 offsets for debug
                        if (offset % 100 == 0) {
                            val sign = if (offset >= 0) "+" else ""
                            sampledChecksums.add("offset $sign$offset → 0x${checksum.toString(16).uppercase().padStart(8, '0')}")
                        }

                        // Collect ALL offsets that match ANY valid AR entry — do NOT break early
                        if (validChecksums.any { it.checksum == checksum }) {
                            matchingOffsets.add(offset)
                        }
                    }
                }

                val discIdUrl = try { accurateRipService.getAccurateRipUrl(toc) } catch (e: Exception) { "Unknown" }

                val stepDebugInfo = CalibrationDebugInfo(
                    discId               = discIdUrl,
                    trackUsed            = resolvedTrackIndex + 1,
                    arTrackNumber        = resolvedArTrackNumber,
                    nativeTrackStart     = nativeTrackStart,
                    normalisedReadStart  = normalisedReadStart,
                    physicalReadStartLba = readStartLba,
                    actualPreSectors     = actualPreSectors,
                    sectorsToRead        = sectorsToRead,
                    totalSectors         = totalSectors,
                    expectedChecksums    = buildList {
                        addAll(validChecksums.map {
                            "0x${it.checksum.toString(16).uppercase().padStart(8, '0')} (conf ${it.confidence})"
                        })
                        val filteredCount = expectedChecksums!!.size - validChecksums.size
                        if (filteredCount > 0) add("[$filteredCount zero-confidence placeholder(s) filtered]")
                    },
                    sampledComputedChecksums = sampledChecksums
                )

                appendStepReport(CalibrationStepReport(
                    stepNumber      = stepIndex + 1,
                    discId          = discIdUrl,
                    matchingOffsets = matchingOffsets,
                    debugInfo       = stepDebugInfo
                ))

                // Record this step's candidate set and transition to Success regardless of
                // whether matchingOffsets is empty — the intersection decides the final result.
                _uiState.update { current ->
                    current.copy(candidateSets = current.candidateSets + listOf(matchingOffsets))
                }
                updateStepState(stepIndex, CalibrationStepState.Success)

            } catch (e: Exception) {
                com.bitperfect.core.utils.AppLogger.e("OffsetCalibration", "Calibration error", e)

                val discIdUrl = try {
                    accurateRipService.getAccurateRipUrl(
                        (DeviceStateManager.driveStatus.value as? DriveStatus.DiscReady)?.toc
                            ?: throw IllegalStateException("TOC unavailable")
                    )
                } catch (ex: Exception) { "Unknown" }
                appendStepReport(CalibrationStepReport(
                    stepNumber      = stepIndex + 1,
                    discId          = discIdUrl,
                    matchingOffsets = emptySet(),
                    debugInfo       = null
                ))
                updateStepState(stepIndex, CalibrationStepState.Error(e.message ?: "Unknown error", null))
            }

            checkCalibrationComplete()
        }
    }

    fun saveOffset(offset: Int) {
        val driveStatus = DeviceStateManager.driveStatus.value
        if (driveStatus !is DriveStatus.DiscReady) {
            _uiState.update { it.copy(saveState = SaveState.Error("Drive disconnected")) }
            return
        }

        val vendorId = driveStatus.info.vendorId
        val productId = driveStatus.info.productId

        _uiState.update { it.copy(saveState = SaveState.Saving) }
        viewModelScope.launch {
            try {
                driveOffsetRepository.saveCalibratedOffset(vendorId, productId, offset)
                _uiState.update { it.copy(saveState = SaveState.Finished) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saveState = SaveState.Error(e.message ?: "Failed to save offset")) }
            }
        }
    }

    fun resetSaveState() {
        _uiState.update { it.copy(saveState = SaveState.Idle) }
    }

    fun setActiveStepIndex(index: Int) {
        _uiState.update { it.copy(activeStepIndex = index) }
    }

    fun resetStep(index: Int) {
        updateStepState(index, CalibrationStepState.WaitingForDisc)
    }

    private fun checkCalibrationComplete() {
        val state = _uiState.value

        // Wait until all 3 steps are in a terminal state (Success or Error).
        // A step in Error contributes an empty set which will make the intersection empty.
        val allTerminal = state.steps.all {
            it is CalibrationStepState.Success || it is CalibrationStepState.Error
        }
        if (!allTerminal) return

        // Only compute result once all 3 candidate sets are recorded.
        // candidateSets may have fewer than 3 entries if some steps errored before
        // appending their set — treat missing sets as empty.
        val sets = List(3) { i -> state.candidateSets.getOrElse(i) { emptySet() } }

        val intersection = sets[0]
            .intersect(sets[1])
            .intersect(sets[2])

        val finalOffset: Int
        val passed: Boolean

        when {
            intersection.size == 1 -> {
                finalOffset = intersection.first()
                passed = true
            }
            intersection.size > 1 -> {
                // Multiple offsets agreed across all 3 discs — extremely unlikely.
                // Pick the one closest to 0 as a tiebreak; flag as low confidence.
                finalOffset = intersection.minByOrNull { kotlin.math.abs(it) } ?: 0
                passed = false
            }
            else -> {
                // Empty intersection — discs don't agree on any single offset.
                finalOffset = 0
                passed = false
            }
        }

        _uiState.update {
            it.copy(calibrationResult = CalibrationResult(offset = finalOffset, passed = passed))
        }
    }
}
