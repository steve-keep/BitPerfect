package com.bitperfect.app.usb

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE)
class UsbReadSessionTest {

    private val fakeSector = ByteArray(2352) { it.toByte() }

    @Test
    fun `readWithRetry returns data on first success`() {
        var calls = 0
        val result = readWithRetry(
            execute     = { _, _ -> calls++; fakeSector },
            isDiscReady = { true },
            lba = 1, sectorCount = 1
        )
        assertEquals(fakeSector, result)
        assertEquals(1, calls)
    }

    @Test
    fun `readWithRetry retries on null and returns data on second attempt`() {
        var calls = 0
        val result = readWithRetry(
            execute     = { _, _ -> calls++; if (calls < 2) null else fakeSector },
            isDiscReady = { true },
            lba = 1, sectorCount = 1
        )
        assertEquals(fakeSector, result)
        assertEquals(2, calls)
    }

    @Test
    fun `readWithRetry returns null after maxRetries all fail`() {
        var calls = 0
        val result = readWithRetry(
            execute     = { _, _ -> calls++; null },
            isDiscReady = { true },
            lba = 1, sectorCount = 1,
            maxRetries  = 3
        )
        assertNull(result)
        assertEquals(3, calls)
    }

    @Test
    fun `readWithRetry returns null immediately when drive becomes unready`() {
        var calls = 0
        val result = readWithRetry(
            execute     = { _, _ -> calls++; null },
            isDiscReady = { false },   // drive not ready from the start
            lba = 1, sectorCount = 1
        )
        assertNull(result)
        assertEquals(1, calls)   // first attempt fires, then isDiscReady() → false → stop
    }

    @Test
    fun `readWithRetry passes lba and sectorCount through to execute`() {
        var capturedLba = -1
        var capturedCount = -1
        readWithRetry(
            execute     = { lba, count -> capturedLba = lba; capturedCount = count; fakeSector },
            isDiscReady = { true },
            lba = 42, sectorCount = 8
        )
        assertEquals(42, capturedLba)
        assertEquals(8, capturedCount)
    }
}
