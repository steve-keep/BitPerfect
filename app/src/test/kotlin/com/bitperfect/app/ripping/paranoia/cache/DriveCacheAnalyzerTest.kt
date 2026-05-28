package com.bitperfect.app.ripping.paranoia.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveCacheAnalyzerTest {

    private val analyzer = DefaultDriveCacheAnalyzer()

    @Test
    fun `test cache unlikely with single read`() {
        val attempts = listOf(
            ReadAttempt(0, 100, 25.0, false, false, emptyList(), 12345L)
        )
        val result = analyzer.analyze(attempts)
        assertEquals(CacheStatus.CACHE_UNLIKELY, result.status)
        assertEquals(0.0f, result.suspicionScore)
    }

    @Test
    fun `test cache suspected with fast identical rereads`() {
        val attempts = listOf(
            ReadAttempt(0, 100, 30.0, false, false, emptyList(), 12345L),
            ReadAttempt(0, 100, 2.0, true, true, emptyList(), 12345L),
            ReadAttempt(0, 100, 2.1, true, true, emptyList(), 12345L)
        )
        val result = analyzer.analyze(attempts)
        // Ratio < 0.2 (+0.35), 2 identical (+0.45 * 2/2), variance < 2.0 (+0.20) -> sum = 1.0
        assertEquals(CacheStatus.CACHE_CONFIRMED, result.status)
        assertEquals(1.0f, result.suspicionScore, 0.01f)
    }

    @Test
    fun `test cache unlikely with slow non-identical rereads`() {
        val attempts = listOf(
            ReadAttempt(0, 100, 30.0, false, false, emptyList(), 12345L),
            ReadAttempt(0, 100, 25.0, true, false, emptyList(), 12346L),
            ReadAttempt(0, 100, 32.0, true, false, emptyList(), 12347L)
        )
        val result = analyzer.analyze(attempts)
        // Ratio ~ 0.95 (+0.0), 0 identical (+0.0), variance > 2.0 (+0.0) -> sum = 0.0
        assertEquals(CacheStatus.CACHE_UNLIKELY, result.status)
        assertEquals(0.0f, result.suspicionScore, 0.01f)
    }
}
