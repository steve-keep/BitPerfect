package com.bitperfect.app.ripping.logging

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.usb.RipStatus
import com.bitperfect.app.ripping.streaming.StreamingClassification
import com.bitperfect.app.ripping.paranoia.cache.CacheStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

class ForensicRipLoggerTest {

    private lateinit var logger: DefaultForensicRipLogger
    private lateinit var mockContext: Context
    private lateinit var mockDir: DocumentFile
    private lateinit var mockFile: DocumentFile
    private lateinit var outputStream: ByteArrayOutputStream


    @Before
    fun setup() {
        logger = DefaultForensicRipLogger()
        outputStream = ByteArrayOutputStream()

        mockFile = org.mockito.Mockito.mock(DocumentFile::class.java)
        org.mockito.Mockito.`when`(mockFile.name).thenReturn("bitperfect-rip-log.txt")
        org.mockito.Mockito.`when`(mockFile.uri).thenReturn(Uri.parse("content://mock"))

        mockDir = org.mockito.Mockito.mock(DocumentFile::class.java)
        org.mockito.Mockito.`when`(mockDir.name).thenReturn("dir")
        org.mockito.Mockito.`when`(mockDir.isDirectory).thenReturn(true)
        org.mockito.Mockito.`when`(mockDir.createFile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(mockFile)

        val mockContentResolver = org.mockito.Mockito.mock(android.content.ContentResolver::class.java)
        // Correct the when clause for contentResolver to accept the exact uri that DocumentFile returns
        org.mockito.Mockito.`when`(mockContentResolver.openOutputStream(Uri.parse("content://mock"))).thenReturn(outputStream)

        mockContext = org.mockito.Mockito.mock(Context::class.java)
        org.mockito.Mockito.`when`(mockContext.contentResolver).thenReturn(mockContentResolver)
    }


    @Test
    fun `test complete log generation`() {
        logger.record(
            RipLogEvent.SessionStarted(
                appVersion = "BitPerfect 1.2.0",
                deviceModel = "Pixel 8a",
                androidVersion = "Android 16",
                timestampIso = "2026-05-29T10:42:11Z",
                mode = RipMode.SECURE,
                chunkSize = 16,
                overlapSize = 6,
                driveVendor = "ASUS",
                driveModel = "SDRW-08D2S-U",
                driveFirmware = "1.00",
                albumTitle = "Test Album",
                artistName = "Test Artist"
            )
        )

        logger.record(RipLogEvent.TrackStarted(1, "Track 1"))

        logger.record(RipLogEvent.OverlapMismatchDetected(
            trackNumber = 2,
            lbaStart = 125430,
            lbaEnd = 125446
        ))

        logger.record(RipLogEvent.RecoverySucceeded(
            trackNumber = 2,
            lbaStart = 125430,
            lbaEnd = 125446,
            rereadAttempts = 3
        ))

        logger.record(RipLogEvent.TrackCompleted(
            trackNumber = 1,
            confidence = RipConfidence.HIGH,
            rereads = 0,
            suspiciousReads = 0,
            status = RipStatus.SUCCESS,
            accurateRipStatus = "VERIFIED",
            computedChecksumV1 = 0x12345678L,
            computedChecksumV2 = 0x87654321L,
            expectedChecksumsV1 = listOf(0x12345678L, 0xABCDEF01L),
            expectedChecksumsV2 = listOf(0x87654321L, 0x10FEDCBAL),
            startLba = 0,
            endLba = 18412,
            totalSectors = 18412,
            sectorsRead = 18412,
            durationSeconds = 245.5,
            summary = RipLogEvent.TrackRipSummary(10, 10, 0, 10, 0, 0, 0, 10)
        ))

        logger.record(RipLogEvent.TrackCompleted(
            trackNumber = 2,
            confidence = RipConfidence.MEDIUM,
            rereads = 4,
            suspiciousReads = 1,
            status = RipStatus.WARNING,
            accurateRipStatus = "MISMATCH",
            computedChecksumV1 = 0x11111111L,
            computedChecksumV2 = null,
            expectedChecksumsV1 = listOf(0x22222222L),
            expectedChecksumsV2 = emptyList(),
            startLba = 18412,
            endLba = 27412,
            totalSectors = 9000,
            sectorsRead = 9000,
            durationSeconds = 120.0,
            summary = RipLogEvent.TrackRipSummary(10, 10, 0, 10, 0, 0, 0, 10)
        ))

        logger.record(RipLogEvent.DriveAnalysisCompleted(
            profile = com.bitperfect.app.ripping.capability.DriveProfile(
                vendor = "Test",
                model = "Drive",
                firmware = null,
                preferredReadSize = 26,
                maxReliableReadSize = 32,
                supportsStreaming = true,
                likelyCachesAudio = false,
                stableLargeReads = true,
                unstableSeeking = false,
                retrySuccessRate = 0.0f,
                overlapInstabilityRate = 0.0f
            ),
            cacheProbeResult = null,
            streamingAnalysisResult = null,
            readSizeProfile = null
        ))

        logger.record(RipLogEvent.SessionCompleted(success = true))

        logger.finalize(mockContext, mockDir)


        val output = String(outputStream.toByteArray())
        println("!!! OUTPUT START !!!")
        println(output)
        println("!!! OUTPUT END !!!")


        println("OUTPUT:\n" + output)
        assertTrue(output.contains("BitPerfect Forensic Rip Log"))
        assertTrue(output.contains("Timestamp: 2026-05-29T10:42:11Z"))
        assertTrue(output.contains("Application: BitPerfect 1.2.0"))
        assertTrue(output.contains("Mode: Secure"))
        assertTrue(output.contains("Vendor: ASUS"))
        assertTrue(output.contains("Model: SDRW-08D2S-U"))
        assertTrue(output.contains("Firmware: 1.00"))
        assertTrue(output.contains("Cache Status: CACHE_UNLIKELY"))
        assertTrue(output.contains("Streaming: STABLE_STREAMING"))
        assertTrue(output.contains("Preferred Read Size: 26"))
        assertTrue(output.contains("Track 01"))
        assertTrue(output.contains("Confidence: HIGH"))
        assertTrue(output.contains("AccurateRip: VERIFIED"))
        assertTrue(output.contains("Track 02"))
        assertTrue(output.contains("Confidence: MEDIUM"))
        assertTrue(output.contains("Rereads: 4"))
        assertTrue(output.contains("AccurateRip: MISMATCH"))
    }
}
