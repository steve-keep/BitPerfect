package com.bitperfect.app.usb

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.*
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupOutputDirectoryTest {

    private lateinit var context: Context
    private lateinit var parentDir: DocumentFile
    private lateinit var resolver: DirectoryResolver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        parentDir = mock(DocumentFile::class.java)
        `when`(parentDir.exists()).thenReturn(true)
        `when`(parentDir.isDirectory).thenReturn(true)
        resolver = DirectoryResolver { _, _ -> parentDir }
    }

    @Test
    fun setupOutputDirectory_validUriWithCleanNames_returnsBothDirs() {
        val artistDir = mock(DocumentFile::class.java)
        val albumDir = mock(DocumentFile::class.java)

        `when`(parentDir.findFile("Test Artist")).thenReturn(null)
        `when`(parentDir.createDirectory("Test Artist")).thenReturn(artistDir)
        `when`(artistDir.findFile("Test Album")).thenReturn(null)
        `when`(artistDir.createDirectory("Test Album")).thenReturn(albumDir)

        val dirs = setupOutputDirectory(context, "content://test", "Test Artist", "Test Album", resolver)

        assertNotNull(dirs.artistDir)
        assertNotNull(dirs.albumDir)
        assertEquals(artistDir, dirs.artistDir)
        assertEquals(albumDir, dirs.albumDir)
    }

    @Test
    fun setupOutputDirectory_artistAlbumNamesWithSlashes_sanitizesNames() {
        val artistDir = mock(DocumentFile::class.java)
        val albumDir = mock(DocumentFile::class.java)

        `when`(parentDir.findFile("Artist _ Name")).thenReturn(null)
        `when`(parentDir.createDirectory("Artist _ Name")).thenReturn(artistDir)
        `when`(artistDir.findFile("Album _ Title")).thenReturn(null)
        `when`(artistDir.createDirectory("Album _ Title")).thenReturn(albumDir)

        val dirs = setupOutputDirectory(context, "content://test", "Artist / Name", "Album / Title", resolver)

        assertNotNull(dirs.artistDir)
        assertNotNull(dirs.albumDir)
        assertEquals(artistDir, dirs.artistDir)
        assertEquals(albumDir, dirs.albumDir)
    }

    @Test
    fun setupOutputDirectory_fromTreeUriReturnsNull_throwsIOException() {
        resolver = DirectoryResolver { _, _ -> null }

        val exception = assertThrows(IOException::class.java) {
            setupOutputDirectory(context, "content://test", "Artist", "Album", resolver)
        }
        assertTrue(exception.message!!.contains("Invalid output directory"))
    }

    @Test
    fun setupOutputDirectory_createArtistDirectoryFails_throwsIOException() {
        `when`(parentDir.findFile("Artist")).thenReturn(null)
        `when`(parentDir.createDirectory("Artist")).thenReturn(null)

        val exception = assertThrows(IOException::class.java) {
            setupOutputDirectory(context, "content://test", "Artist", "Album", resolver)
        }
        assertTrue(exception.message!!.contains("Could not create artist directory"))
    }

    @Test
    fun setupOutputDirectory_createAlbumDirectoryFails_throwsIOException() {
        val artistDir = mock(DocumentFile::class.java)
        `when`(parentDir.findFile("Artist")).thenReturn(artistDir)

        `when`(artistDir.findFile("Album")).thenReturn(null)
        `when`(artistDir.createDirectory("Album")).thenReturn(null)

        val exception = assertThrows(IOException::class.java) {
            setupOutputDirectory(context, "content://test", "Artist", "Album", resolver)
        }
        assertTrue(exception.message!!.contains("Could not create album directory"))
    }
}
