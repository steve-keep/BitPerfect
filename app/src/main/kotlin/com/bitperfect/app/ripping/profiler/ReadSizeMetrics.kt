package com.bitperfect.app.ripping.profiler

data class ReadSizeMetrics(
    val readSize: Int,
    val attempts: Int,
    val successfulReads: Int,
    val overlapFailures: Int,
    val transportFailures: Int,
    val shortReads: Int,
    val rereadEscalations: Int
)
