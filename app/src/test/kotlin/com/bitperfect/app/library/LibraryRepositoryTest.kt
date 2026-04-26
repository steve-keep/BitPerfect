package com.bitperfect.app.library

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.fakes.RoboCursor
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var libraryRepository: LibraryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        libraryRepository = LibraryRepository(context)
    }

    @Test
    fun getLibrary_nullUri_returnsEmptyList() {
        val library = libraryRepository.getLibrary(null)
        assertEquals(0, library.size)
    }

    @Test
    fun getLibrary_emptyUri_returnsEmptyList() {
        val library = libraryRepository.getLibrary("")
        assertEquals(0, library.size)
    }

    @Test
    fun getLibrary_invalidUri_returnsEmptyList() {
        val library = libraryRepository.getLibrary("content://com.android.externalstorage.documents/tree/primary")
        assertEquals(0, library.size)
    }

    @Test
    fun getLibrary_deduplicatesAlbumsByTitle() {
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM
        ))

        // Simulating multi-disc album: same artist, same album title, different album IDs
        cursor.setResults(arrayOf(
            arrayOf(1L, "Counting Crows", 101L, "Films About Ghosts: The Best Of..."),
            arrayOf(1L, "Counting Crows", 102L, "Films About Ghosts: The Best Of...")
        ))

        val shadowContentResolver = shadowOf(context.contentResolver)
        shadowContentResolver.setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor)

        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBitPerfect"
        val library = libraryRepository.getLibrary(uri)

        assertEquals(1, library.size)
        val artist = library[0]
        assertEquals("Counting Crows", artist.name)
        assertEquals(1L, artist.id)

        // Verify deduplication
        assertEquals(1, artist.albums.size)
        val album = artist.albums[0]
        assertEquals("Films About Ghosts: The Best Of...", album.title)
        assertEquals(101L, album.id) // First encountered ID
        assertEquals(Uri.parse("content://media/external/audio/albumart/101"), album.artUri)
    }

    @Test
    fun getLibrary_handlesCaseInsensitiveDeduplication() {
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM
        ))

        // Simulating same album title but different casing
        cursor.setResults(arrayOf(
            arrayOf(1L, "Counting Crows", 101L, "Films About Ghosts"),
            arrayOf(1L, "Counting Crows", 102L, "films about ghosts")
        ))

        val shadowContentResolver = shadowOf(context.contentResolver)
        shadowContentResolver.setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor)

        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FBitPerfect"
        val library = libraryRepository.getLibrary(uri)

        assertEquals(1, library.size)
        val artist = library[0]

        // Verify deduplication
        assertEquals(1, artist.albums.size)
        val album = artist.albums[0]
        assertEquals("Films About Ghosts", album.title)
        assertEquals(101L, album.id)
    }
}
