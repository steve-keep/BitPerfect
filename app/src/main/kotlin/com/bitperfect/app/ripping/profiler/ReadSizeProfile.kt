package com.bitperfect.app.ripping.profiler

data class ReadSizeProfile(
    val preferredReadSize: Int,
    val maxReliableReadSize: Int,
    val unstableSizes: Set<Int>,
    val metrics: List<ReadSizeMetrics>
)
