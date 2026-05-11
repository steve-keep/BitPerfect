package com.bitperfect.app.ui.calibration

data class CalibrationDebugInfo(
    val discId: String,
    val trackUsed: Int,
    val arTrackNumber: Int,
    val nativeTrackStart: Int,
    val readStartLba: Int,
    val actualPreSectors: Int,
    val sectorsToRead: Int,
    val totalSectors: Int,
    val expectedChecksums: List<String>,
    val sampledComputedChecksums: List<String>
)

sealed class SaveState {
    data object Idle : SaveState()
    data object Saving : SaveState()
    data object Finished : SaveState()
    data class Error(val message: String) : SaveState()
}

data class OffsetCalibrationUiState(
    val steps: List<CalibrationStepState> = listOf(
        CalibrationStepState.WaitingForDisc,
        CalibrationStepState.WaitingForDisc,
        CalibrationStepState.WaitingForDisc
    ),
    val activeStepIndex: Int = 0,
    val calibrationResult: CalibrationResult? = null,
    val saveState: SaveState = SaveState.Idle
)
