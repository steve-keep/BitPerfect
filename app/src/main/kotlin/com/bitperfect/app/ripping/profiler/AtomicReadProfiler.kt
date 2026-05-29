package com.bitperfect.app.ripping.profiler

interface AtomicReadProfiler {
    fun analyze(metrics: List<ReadSizeMetrics>): ReadSizeProfile
}
