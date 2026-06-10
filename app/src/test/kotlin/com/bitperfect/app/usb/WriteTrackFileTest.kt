package com.bitperfect.app.usb

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.times
import org.mockito.Mockito.never
import org.mockito.ArgumentMatchers.any
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteTrackFileTest {

    private fun makeRipManager(context: Context = ApplicationProvider.getApplicationContext()): RipManager {
        return RipManager(
            context = context,
            outputFolderUriString = "",
            toc = DiscToc(emptyList(), 0, 0),
            metadata = DiscMetadata("", "", emptyList(), mbReleaseId = ""),
            expectedChecksums = emptyList(),
            artworkBytes = null,
            lyricsMap = emptyMap(),
            driveVendor = "TEST",
            driveProduct = "TEST",
            initialTracks = emptyList()
        )
    }

    @Test
    fun writeTrackFile_success_writesMetadataThenPcm() {
        val mockContext = mock(Context::class.java)
        val mockResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockResolver)

        val destFile = mock(DocumentFile::class.java)
        val testUri = Uri.parse("content://test")
        `when`(destFile.uri).thenReturn(testUri)
        `when`(destFile.name).thenReturn("test.flac")

        val outputStream = ByteArrayOutputStream()
        `when`(mockResolver.openOutputStream(testUri)).thenReturn(outputStream)

        val metadataBytes = byteArrayOf(1, 2, 3)
        val tempFile = File.createTempFile("test_audio", ".pcm")
        tempFile.writeBytes(byteArrayOf(4, 5, 6))

        val ripManager = makeRipManager(mockContext)
        val result = ripManager.writeTrackFile(mockContext, destFile, metadataBytes, tempFile)

        assertTrue(result is WriteTrackResult.Success)

        val writtenBytes = outputStream.toByteArray()
        assertEquals(6, writtenBytes.size)
        assertEquals(1.toByte(), writtenBytes[0])
        assertEquals(2.toByte(), writtenBytes[1])
        assertEquals(3.toByte(), writtenBytes[2])
        assertEquals(4.toByte(), writtenBytes[3])
        assertEquals(5.toByte(), writtenBytes[4])
        assertEquals(6.toByte(), writtenBytes[5])

        tempFile.delete()
    }

    @Test
    fun writeTrackFile_openOutputStreamReturnsNull_returnsFailed() {
        val mockContext = mock(Context::class.java)
        val mockResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockResolver)

        val destFile = mock(DocumentFile::class.java)
        val testUri = Uri.parse("content://test")
        `when`(destFile.uri).thenReturn(testUri)
        `when`(destFile.name).thenReturn("test.flac")

        `when`(mockResolver.openOutputStream(testUri)).thenReturn(null)

        val metadataBytes = byteArrayOf(1, 2, 3)
        val tempFile = File.createTempFile("test_audio", ".pcm")

        val ripManager = makeRipManager(mockContext)
        val result = ripManager.writeTrackFile(mockContext, destFile, metadataBytes, tempFile)

        assertTrue(result is WriteTrackResult.Failed)
        val failed = result as WriteTrackResult.Failed
        assertTrue(failed.reason.contains("openOutputStream"))

        verify(destFile, never()).delete()

        tempFile.delete()
    }

    @Test
    fun writeTrackFile_exceptionDuringWrite_returnsFailedAndDeletesDest() {
        val mockContext = mock(Context::class.java)
        val mockResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockResolver)

        val destFile = mock(DocumentFile::class.java)
        val testUri = Uri.parse("content://test")
        `when`(destFile.uri).thenReturn(testUri)
        `when`(destFile.name).thenReturn("test.flac")

        val outputStream = object : java.io.OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk full")
            }
            override fun write(b: ByteArray) {
                throw IOException("disk full")
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("disk full")
            }
        }
        `when`(mockResolver.openOutputStream(testUri)).thenReturn(outputStream)

        val metadataBytes = byteArrayOf(1, 2, 3)
        val tempFile = File.createTempFile("test_audio", ".pcm")
        tempFile.writeBytes(byteArrayOf(4, 5, 6))

        val ripManager = makeRipManager(mockContext)
        val result = ripManager.writeTrackFile(mockContext, destFile, metadataBytes, tempFile)

        assertTrue(result is WriteTrackResult.Failed)
        val failed = result as WriteTrackResult.Failed
        assertTrue(failed.reason.contains("disk full"))

        verify(destFile, times(1)).delete()

        tempFile.delete()
    }

    @Test
    fun writeTrackFile_tempFileEmpty_writesMetadataOnly() {
        val mockContext = mock(Context::class.java)
        val mockResolver = mock(ContentResolver::class.java)
        `when`(mockContext.contentResolver).thenReturn(mockResolver)

        val destFile = mock(DocumentFile::class.java)
        val testUri = Uri.parse("content://test")
        `when`(destFile.uri).thenReturn(testUri)
        `when`(destFile.name).thenReturn("test.flac")

        val outputStream = ByteArrayOutputStream()
        `when`(mockResolver.openOutputStream(testUri)).thenReturn(outputStream)

        val metadataBytes = byteArrayOf(1, 2, 3)
        val tempFile = File.createTempFile("test_audio", ".pcm")
        // Empty temp file

        val ripManager = makeRipManager(mockContext)
        val result = ripManager.writeTrackFile(mockContext, destFile, metadataBytes, tempFile)

        assertTrue(result is WriteTrackResult.Success)

        val writtenBytes = outputStream.toByteArray()
        assertEquals(3, writtenBytes.size)
        assertEquals(1.toByte(), writtenBytes[0])
        assertEquals(2.toByte(), writtenBytes[1])
        assertEquals(3.toByte(), writtenBytes[2])

        tempFile.delete()
    }
}