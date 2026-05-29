package com.bitperfect.app.ripping.profiler

class ReadSizeMetricsCollector {
    private val metricsMap = mutableMapOf<Int, MutableMetrics>()

    private data class MutableMetrics(
        var attempts: Int = 0,
        var successfulReads: Int = 0,
        var overlapFailures: Int = 0,
        var transportFailures: Int = 0,
        var shortReads: Int = 0,
        var rereadEscalations: Int = 0
    )

    private fun getMetrics(readSize: Int): MutableMetrics {
        return metricsMap.getOrPut(readSize) { MutableMetrics() }
    }

    fun recordAttempt(readSize: Int) {
        getMetrics(readSize).attempts++
    }

    fun recordSuccessfulRead(readSize: Int) {
        getMetrics(readSize).successfulReads++
    }

    fun recordOverlapFailure(readSize: Int) {
        getMetrics(readSize).overlapFailures++
    }

    fun recordTransportFailure(readSize: Int) {
        getMetrics(readSize).transportFailures++
    }

    fun recordShortRead(readSize: Int) {
        getMetrics(readSize).shortReads++
    }

    fun recordRereadEscalation(readSize: Int) {
        getMetrics(readSize).rereadEscalations++
    }

    fun build(): List<ReadSizeMetrics> {
        return metricsMap.map { (readSize, metrics) ->
            ReadSizeMetrics(
                readSize = readSize,
                attempts = metrics.attempts,
                successfulReads = metrics.successfulReads,
                overlapFailures = metrics.overlapFailures,
                transportFailures = metrics.transportFailures,
                shortReads = metrics.shortReads,
                rereadEscalations = metrics.rereadEscalations
            )
        }
    }
}
