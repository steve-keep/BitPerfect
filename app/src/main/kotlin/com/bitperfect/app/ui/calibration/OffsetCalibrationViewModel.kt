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
            // Randomly succeed with a mock offset for now.
            // A real implementation would call AccurateRipVerifier here.
            val mockOffset = 667
            candidateOffsets.add(mockOffset)

            updateStepState(stepIndex, CalibrationStepState.Success)

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
