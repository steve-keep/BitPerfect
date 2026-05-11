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

    private val candidateOffsets = mutableListOf<Int>()

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

    fun startScan(stepIndex: Int) {
        val state = _uiState.value
        if (state.steps.getOrNull(stepIndex) !is CalibrationStepState.KeyDiscConfirmed) {
            return
        }

        updateStepState(stepIndex, CalibrationStepState.Scanning)

        viewModelScope.launch {
            try {
                val driveStatus = DeviceStateManager.driveStatus.value as? DriveStatus.DiscReady
                    ?: throw IllegalStateException("Drive not ready")
                val toc = driveStatus.toc ?: throw IllegalStateException("TOC not available")

                var expectedChecksums: List<com.bitperfect.core.services.AccurateRipTrackMetadata>? = null
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    expectedChecksums = accurateRipService.getExpectedChecksums(toc)[1]
                }

                if (expectedChecksums == null) {
                    throw IllegalStateException("No AccurateRip checksums found for Track 1")
                }

                val transport = DeviceStateManager.getTransport()
                val inEndpoint = DeviceStateManager.getInEndpoint()
                val outEndpoint = DeviceStateManager.getOutEndpoint()

                if (transport == null || inEndpoint == null || outEndpoint == null) {
                    throw IllegalStateException("USB endpoints not available")
                }

                val readCmd = com.bitperfect.app.usb.ReadCdCommand(transport, outEndpoint, inEndpoint)

                val track = toc.tracks.first()
                val nextLba = if (toc.tracks.size > 1) toc.tracks[1].lba else toc.leadOutLba
                val totalSectors = nextLba - track.lba
                val totalSamples = totalSectors.toLong() * 588L

                val (firstLba, sectorsToRead) = calibrationLbaRange(
                    trackLba       = track.lba,
                    pregapOffset   = toc.pregapOffset,
                    totalSectors   = totalSectors
                )

                val rawBuffer = java.io.ByteArrayOutputStream()
                var sectorsRead = 0
                val chunkSize = 8

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (sectorsRead < sectorsToRead) {
                        val chunk = minOf(chunkSize, sectorsToRead - sectorsRead)
                        var pcmData: ByteArray? = null
                        for (attempt in 1..3) {
                            pcmData = readCmd.execute(firstLba + sectorsRead, chunk)
                            if (pcmData != null) break
                            if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) break
                        }
                        if (pcmData == null) {
                            throw java.io.IOException("Failed to read audio data")
                        }
                        rawBuffer.write(pcmData)
                        sectorsRead += (pcmData.size / 2352)
                    }
                }

                val fullPcm = rawBuffer.toByteArray()
                var foundOffset: Int? = null
                val verifier = com.bitperfect.core.services.AccurateRipVerifier()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val trackBuffer = ByteArray(totalSectors * 2352)

                    for (offset in -3000..3000) {
                        if (!isActive) break

                        if (offset < 0) {
                            // Negative offset: drive reads too late.
                            // To simulate correcting this, we need data from before the read start.
                            // We pad the beginning with silence.
                            val silenceSamples = -offset
                            val silenceBytes = silenceSamples * 4
                            val dataToCopy = trackBuffer.size - silenceBytes
                            trackBuffer.fill(0.toByte(), 0, silenceBytes)
                            System.arraycopy(fullPcm, 0, trackBuffer, silenceBytes, dataToCopy)
                        } else {
                            // Positive offset: drive reads too early.
                            // We shift forward into the read buffer.
                            val shiftBytes = offset * 4
                            System.arraycopy(fullPcm, shiftBytes, trackBuffer, 0, trackBuffer.size)
                        }

                        val result = verifier.computeChecksumChunk(
                            pcmData = trackBuffer,
                            samplePosition = 1L,
                            totalSamples = totalSamples,
                            isFirstTrack = true,
                            isLastTrack = toc.tracks.size == 1
                        )

                        val checksum = verifier.finaliseChecksum(result.partialChecksum)

                        if (expectedChecksums!!.any { it.checksum == checksum }) {
                            foundOffset = offset
                            break
                        }
                    }
                }

                if (foundOffset != null) {
                    candidateOffsets.add(foundOffset!!)
                    updateStepState(stepIndex, CalibrationStepState.Success)
                } else {
                    updateStepState(stepIndex, CalibrationStepState.Error("No matching offset found (-3000 to +3000)", null))
                }

            } catch (e: Exception) {
                com.bitperfect.core.utils.AppLogger.e("OffsetCalibration", "Calibration error", e)
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
        if (state.steps.all { it is CalibrationStepState.Success }) {
            // Compute final offset based on Step C rules
            val pass: Boolean
            val finalOffset: Int

            if (candidateOffsets.size == 3) {
                val o1 = candidateOffsets[0]
                val o2 = candidateOffsets[1]
                val o3 = candidateOffsets[2]

                if (o1 == o2) {
                    finalOffset = o1
                    pass = true
                } else if (o2 == o3) {
                    finalOffset = o2
                    pass = true
                } else if (o1 == o3) {
                    finalOffset = o1
                    pass = true
                } else {
                    finalOffset = 0
                    pass = false
                }
            } else {
                finalOffset = 0
                pass = false
            }

            _uiState.update {
                it.copy(
                    calibrationResult = CalibrationResult(offset = finalOffset, passed = pass)
                )
            }
        }
    }
}
