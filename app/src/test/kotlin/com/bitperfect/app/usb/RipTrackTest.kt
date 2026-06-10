package com.bitperfect.app.usb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.app.ripping.logging.DefaultForensicRipLogger
import com.bitperfect.app.ripping.paranoia.RipConfidence
import com.bitperfect.app.ripping.profiler.ReadSizeMetricsCollector
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import com.bitperfect.app.usb.TrackRipResult
import com.bitperfect.core.services.AccurateRipDiscPressing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyInt
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RipTrackTest {

    private fun makeRipManager(): RipManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val toc = DiscToc(
            tracks = listOf(TocEntry(trackNumber = 1, lba = 0)),
            pregapOffset = 0,
            leadOutLba = 100
        )
        val metadata = DiscMetadata(
            artistName = "Artist",
            albumTitle = "Album",
            trackTitles = listOf("Track 1"),
            discNumber = 1,
            totalDiscs = 1,
            mbReleaseId = ""
        )
        return RipManager(
            context = context,
            outputFolderUriString = "content://test",
            toc = toc,
            metadata = metadata,
            expectedChecksums = emptyList(),
            artworkBytes = null,
            lyricsMap = emptyMap(),
            driveVendor = "TEST",
            driveProduct = "TEST",
            initialTracks = listOf(1)
        )
    }

    private fun createMockDocumentFile(): DocumentFile {
        val destFile = mock(DocumentFile::class.java)
        `when`(destFile.uri).thenReturn(android.net.Uri.parse("content://test/dest.flac"))
        return destFile
    }

    private val defaultToc = DiscToc(
        tracks = listOf(TocEntry(trackNumber = 1, lba = 0)),
        leadOutLba = 3
    )
    private val defaultMetadata = DiscMetadata("Artist", "Album", listOf("Track 1"), mbReleaseId = "", discNumber = 1, totalDiscs = 1)

    @Test
    fun ripTrack_cleanRead_returnsSuccessWithCorrectChecksums() = runBlocking {
        val session = mock(UsbReadSession::class.java)
        `when`(session.readSectors(anyInt(), anyInt())).thenAnswer { invocation ->
            val count = invocation.getArgument<Int>(1)
            ByteArray(count * 2352) // All zeros
        }

        val ripManager = makeRipManager()
        val config = RipConfig.from(0)
        val destFile = createMockDocumentFile()

        val result = ripManager.ripTrack(
            context = ApplicationProvider.getApplicationContext<Context>(),
            trackNumber = 1,
            i = 0,
            entry = defaultToc.tracks[0],
            nextLba = 3,
            totalSectors = 3,
            totalSamples = 3 * 588L,
            trackTitle = "Track 1",
            lyricsResult = null,
            destFile = destFile,
            config = config,
            toc = defaultToc,
            metadata = defaultMetadata,
            accurateRipUrl = null,
            artworkBytes = null,
            expectedChecksums = emptyList(),
            activePressingCandidates = emptySet(),
            session = session,
            metricsCollector = ReadSizeMetricsCollector(),
            logger = DefaultForensicRipLogger(),
            incomingOverreadBuffer = null,
            isCancelled = { false },
            trackStartTimeMs = 0L,
            onProgress = { _, _, _ -> }
        )

        assertTrue("Expected Success, got $result", result is TrackRipResult.Success)
        val success = result as TrackRipResult.Success

        assertEquals("checksumV1 mismatch", 0L, success.checksumV1)
        assertEquals("checksumV2 mismatch", 0L, success.checksumV2)
    }

    @Test
    fun ripTrack_cancelledMidLoop_returnsCancelled() = runBlocking {
        var callCount = 0
        val session = mock(UsbReadSession::class.java)
        `when`(session.readSectors(anyInt(), anyInt())).thenAnswer { invocation ->
            callCount++
            val count = invocation.getArgument<Int>(1)
            ByteArray(count * 2352)
        }

        val ripManager = makeRipManager()
        val destFile = createMockDocumentFile()

        val result = ripManager.ripTrack(
            context = ApplicationProvider.getApplicationContext<Context>(),
            trackNumber = 1,
            i = 0,
            entry = defaultToc.tracks[0],
            nextLba = 300,
            totalSectors = 300,
            totalSamples = 300 * 588L,
            trackTitle = "Track 1",
            lyricsResult = null,
            destFile = destFile,
            config = RipConfig.from(0),
            toc = defaultToc.copy(leadOutLba = 300),
            metadata = defaultMetadata,
            accurateRipUrl = null,
            artworkBytes = null,
            expectedChecksums = emptyList(),
            activePressingCandidates = emptySet(),
            session = session,
            metricsCollector = ReadSizeMetricsCollector(),
            logger = DefaultForensicRipLogger(),
            incomingOverreadBuffer = null,
            isCancelled = { callCount > 0 }, // Cancel after first read
            trackStartTimeMs = 0L,
            onProgress = { _, _, _ -> }
        )

        assertTrue("Expected Cancelled, got $result", result is TrackRipResult.Cancelled)
    }

    @Test
    fun ripTrack_readSectorsReturnsNull_returnsFailed() = runBlocking {
        val session = mock(UsbReadSession::class.java)
        `when`(session.readSectors(anyInt(), anyInt())).thenReturn(null)

        val ripManager = makeRipManager()
        val destFile = createMockDocumentFile()

        val result = ripManager.ripTrack(
            context = ApplicationProvider.getApplicationContext<Context>(),
            trackNumber = 1,
            i = 0,
            entry = defaultToc.tracks[0],
            nextLba = 3,
            totalSectors = 3,
            totalSamples = 3 * 588L,
            trackTitle = "Track 1",
            lyricsResult = null,
            destFile = destFile,
            config = RipConfig.from(0),
            toc = defaultToc,
            metadata = defaultMetadata,
            accurateRipUrl = null,
            artworkBytes = null,
            expectedChecksums = emptyList(),
            activePressingCandidates = emptySet(),
            session = session,
            metricsCollector = ReadSizeMetricsCollector(),
            logger = DefaultForensicRipLogger(),
            incomingOverreadBuffer = null,
            isCancelled = { false },
            trackStartTimeMs = 0L,
            onProgress = { _, _, _ -> }
        )

        assertTrue("Expected Failed, got $result", result is TrackRipResult.Failed)
        val failed = result as TrackRipResult.Failed
        assertTrue(failed.reason.contains("sector", ignoreCase = true) || failed.reason.contains("drive", ignoreCase = true))
    }

    @Test
    fun ripTrack_singleOverlapMismatchThatRecovers_returnsSuccessWithSuspiciousRegion() = runBlocking {
        val config = RipConfig.from(0)
        var readCounter = 0

        val pcmA = ByteArray(config.chunkSize * 2352) { 1.toByte() }
        val pcmB = ByteArray(config.chunkSize * 2352) { 2.toByte() }
        val pcmB_recovered = ByteArray(config.chunkSize * 2352)

        val overlapBytes = config.overlapSize * 2352
        System.arraycopy(pcmA, pcmA.size - overlapBytes, pcmB_recovered, 0, overlapBytes)

        val session = mock(UsbReadSession::class.java)
        `when`(session.readSectors(anyInt(), anyInt())).thenAnswer {
            readCounter++
            if (readCounter == 1) pcmA
            else if (readCounter == 2) pcmB
            else pcmB_recovered
        }

        val ripManager = makeRipManager()
        val destFile = createMockDocumentFile()
        val toc = defaultToc.copy(leadOutLba = config.chunkSize * 2 - config.overlapSize)

        val result = ripManager.ripTrack(
            context = ApplicationProvider.getApplicationContext<Context>(),
            trackNumber = 1,
            i = 0,
            entry = toc.tracks[0],
            nextLba = toc.leadOutLba,
            totalSectors = toc.leadOutLba,
            totalSamples = toc.leadOutLba * 588L,
            trackTitle = "Track 1",
            lyricsResult = null,
            destFile = destFile,
            config = config,
            toc = toc,
            metadata = defaultMetadata,
            accurateRipUrl = null,
            artworkBytes = null,
            expectedChecksums = emptyList(),
            activePressingCandidates = emptySet(),
            session = session,
            metricsCollector = ReadSizeMetricsCollector(),
            logger = DefaultForensicRipLogger(),
            incomingOverreadBuffer = null,
            isCancelled = { false },
            trackStartTimeMs = 0L,
            onProgress = { _, _, _ -> }
        )

        assertTrue("Expected Success, got $result", result is TrackRipResult.Success)
        val success = result as TrackRipResult.Success
        assertTrue("Should have at least 1 suspicious region", success.suspiciousRegions.isNotEmpty())
        assertTrue("Region should be marked recovered", success.suspiciousRegions[0].recovered)
    }

    @Test
    fun ripTrack_positiveDriveOffset_overreadBufferNonNull() = runBlocking {
        val tocWithTwoTracks = DiscToc(
            tracks = listOf(TocEntry(trackNumber = 1, lba = 0), TocEntry(trackNumber = 2, lba = 100)),
            leadOutLba = 200
        )
        val config = RipConfig.from(667)
        val skipBytes = config.skipBytes

        val totalSectors = 3
        val session = mock(UsbReadSession::class.java)
        `when`(session.readSectors(anyInt(), anyInt())).thenAnswer { invocation ->
            val count = invocation.getArgument<Int>(1)
            ByteArray(count * 2352)
        }

        val ripManager = makeRipManager()
        val destFile = createMockDocumentFile()

        val result = ripManager.ripTrack(
            context = ApplicationProvider.getApplicationContext<Context>(),
            trackNumber = 1,
            i = 0,
            entry = defaultToc.tracks[0],
            nextLba = totalSectors,
            totalSectors = totalSectors,
            totalSamples = totalSectors * 588L,
            trackTitle = "Track 1",
            lyricsResult = null,
            destFile = destFile,
            config = config,
            toc = tocWithTwoTracks,
            metadata = defaultMetadata,
            accurateRipUrl = null,
            artworkBytes = null,
            expectedChecksums = emptyList(),
            activePressingCandidates = emptySet(),
            session = session,
            metricsCollector = ReadSizeMetricsCollector(),
            logger = DefaultForensicRipLogger(),
            incomingOverreadBuffer = null,
            isCancelled = { false },
            trackStartTimeMs = 0L,
            onProgress = { _, _, _ -> }
        )

        assertTrue("Expected Success, got $result", result is TrackRipResult.Success)
        val success = result as TrackRipResult.Success
        assertNotNull("overreadBuffer should not be null, got result $result", success.overreadBuffer)
        assertEquals("overreadBuffer size mismatch", 2352 - skipBytes, success.overreadBuffer!!.size)
    }
}
