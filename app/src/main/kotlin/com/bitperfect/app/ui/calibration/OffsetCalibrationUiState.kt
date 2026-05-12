package com.bitperfect.app.ui.calibration

data class CalibrationDebugInfo(
    val discId: String,
    val trackUsed: Int,
    val arTrackNumber: Int,
    val nativeTrackStart: Int,
    val normalisedReadStart: Int,
    val physicalReadStartLba: Int,
    val actualPreSectors: Int,
    val sectorsToRead: Int,
    val totalSectors: Int,
    val expectedChecksums: List<String>,
    val sampledComputedChecksums: List<String>
) {
    fun toShareableText(): String {
        return """
            Calibration Diagnostics

            Disc
            AccurateRip URL: $discId

            Read Geometry
            Track scanned: $trackUsed
            AR track number: $arTrackNumber
            nativeTrackStart: $nativeTrackStart
            normalisedReadStart: $normalisedReadStart
            physicalReadStartLba: $physicalReadStartLba
            actualPreSectors: $actualPreSectors
            sectorsToRead: $sectorsToRead
            totalSectors: $totalSectors

            Expected AR Checksums (${expectedChecksums.size})
${expectedChecksums.joinToString("\n") { "            $it" }}

            Computed Checksums (sampled every 100 offsets)
${sampledComputedChecksums.joinToString("\n") { "            $it" }}
        """.trimIndent()
    }
}

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
