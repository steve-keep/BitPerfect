package com.bitperfect.app.ripping.profiler

import com.bitperfect.core.utils.AppLogger

class DefaultAtomicReadProfiler : AtomicReadProfiler {

    override fun analyze(metrics: List<ReadSizeMetrics>): ReadSizeProfile {
        if (metrics.isEmpty()) {
            return ReadSizeProfile(16, 16, emptySet(), emptyList())
        }

        val unstableSizes = mutableSetOf<Int>()
        var preferredSize = 16
        var maxReliableSize = 16

        // Determine unstable sizes
        for (m in metrics) {
            val failureRate = (m.overlapFailures + m.transportFailures + m.shortReads).toDouble() / m.attempts
            val retryRate = m.rereadEscalations.toDouble() / m.attempts

            if (failureRate > 0.05 || retryRate > 0.1 || m.transportFailures > 0) {
                unstableSizes.add(m.readSize)
                AppLogger.d(
                    "AtomicReadProfiler",
                    "Read size instability detected readSize=${m.readSize} " +
                            "transportFailures=${m.transportFailures} " +
                            "overlapFailures=${m.overlapFailures} classification=UNSTABLE"
                )
            }
        }

        val stableMetrics = metrics.filter { !unstableSizes.contains(it.readSize) }
            .sortedByDescending { it.readSize }

        if (stableMetrics.isNotEmpty()) {
            maxReliableSize = stableMetrics.first().readSize

            // Prefer 16 if stable, else the most stable
            preferredSize = stableMetrics.find { it.readSize == 16 }?.readSize
                ?: stableMetrics.minByOrNull { it.overlapFailures + it.transportFailures }?.readSize
                ?: maxReliableSize
        } else {
             preferredSize = 16
             maxReliableSize = 16
        }

        AppLogger.d(
            "AtomicReadProfiler",
            "preferredReadSize=$preferredSize maxReliableReadSize=$maxReliableSize unstableSizes=$unstableSizes"
        )

        return ReadSizeProfile(
            preferredReadSize = preferredSize,
            maxReliableReadSize = maxReliableSize,
            unstableSizes = unstableSizes,
            metrics = metrics
        )
    }
}
