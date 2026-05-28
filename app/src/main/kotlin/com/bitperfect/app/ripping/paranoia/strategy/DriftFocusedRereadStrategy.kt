package com.bitperfect.app.ripping.paranoia.strategy

import com.bitperfect.app.ripping.paranoia.VerifiedChunk
import com.bitperfect.core.utils.AppLogger
import kotlin.math.max
import kotlin.math.min

class DriftFocusedRereadStrategy(
    private val overlapSizeSectors: Int
) : RecoveryStrategy {

    override val strategyName = "drift_focused_recovery"

    override fun getRecoveryWindow(failedChunk: VerifiedChunk): RecoveryWindow {
        // Narrow reread window around overlap, but bounded
        // Re-read overlap + 2 sectors after
        val startLba = failedChunk.startLba
        val totalSize = failedChunk.endLba - failedChunk.startLba
        val driftWindowSize = min(totalSize, overlapSizeSectors + 2)

        return RecoveryWindow(
            startLba = startLba,
            sectorCount = driftWindowSize
        )
    }

    override suspend fun performAttempt(
        context: RecoveryContext,
        failedChunk: VerifiedChunk,
        readChunk: suspend (lba: Int, sectors: Int) -> VerifiedChunk?
    ): VerifiedChunk? {
        val window = getRecoveryWindow(failedChunk)
        AppLogger.d("DriftFocusedRereadStrategy", "[US-021] Drift-focused recovery. WindowSize=\${window.sectorCount} sectors around overlap")
        return readChunk(window.startLba, window.sectorCount)
    }
}
