package com.bitperfect.app.ripping.profiler

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadSizeMetricsCollectorTest {

    @Test
    fun testMetricsCollection() {
        val collector = ReadSizeMetricsCollector()

        collector.recordAttempt(16)
        collector.recordAttempt(16)
        collector.recordSuccessfulRead(16)
        collector.recordOverlapFailure(16)
        collector.recordRereadEscalation(16)

        collector.recordAttempt(32)
        collector.recordTransportFailure(32)
        collector.recordShortRead(32)

        val metrics = collector.build()

        assertEquals(2, metrics.size)

        val metrics16 = metrics.find { it.readSize == 16 }!!
        assertEquals(2, metrics16.attempts)
        assertEquals(1, metrics16.successfulReads)
        assertEquals(1, metrics16.overlapFailures)
        assertEquals(0, metrics16.transportFailures)
        assertEquals(0, metrics16.shortReads)
        assertEquals(1, metrics16.rereadEscalations)

        val metrics32 = metrics.find { it.readSize == 32 }!!
        assertEquals(1, metrics32.attempts)
        assertEquals(0, metrics32.successfulReads)
        assertEquals(0, metrics32.overlapFailures)
        assertEquals(1, metrics32.transportFailures)
        assertEquals(1, metrics32.shortReads)
        assertEquals(0, metrics32.rereadEscalations)
    }
}
