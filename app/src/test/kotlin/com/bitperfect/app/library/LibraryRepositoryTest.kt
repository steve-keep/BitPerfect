package com.bitperfect.app.library

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibraryRepositoryTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockContentResolver: ContentResolver

    private lateinit var libraryRepository: LibraryRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.contentResolver).thenReturn(mockContentResolver)
        libraryRepository = LibraryRepository(mockContext)
    }

    @Test
    fun `getTracksForAlbum returns parsed tracks`() {
        val albumId = 123L
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION
            )
        )

        cursor.addRow(arrayOf(1L, "Track 1", 1, 1000L))
        cursor.addRow(arrayOf(2L, "Track 2", 2, 2000L))

        `when`(
            mockContentResolver.query(
                eq(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
                any(),
                eq("${MediaStore.Audio.Media.ALBUM_ID} = ?"),
                eq(arrayOf(albumId.toString())),
                eq("${MediaStore.Audio.Media.TRACK} ASC")
            )
        ).thenReturn(cursor)

        val tracks = libraryRepository.getTracksForAlbum(albumId)

        assertEquals(2, tracks.size)

        assertEquals(1L, tracks[0].id)
        assertEquals("Track 1", tracks[0].title)
        assertEquals(1, tracks[0].trackNumber)
        assertEquals(1000L, tracks[0].durationMs)
        assertEquals(1, tracks[0].discNumber)

        assertEquals(2L, tracks[1].id)
        assertEquals("Track 2", tracks[1].title)
        assertEquals(2, tracks[1].trackNumber)
        assertEquals(2000L, tracks[1].durationMs)
        assertEquals(1, tracks[1].discNumber)
    }

    @Test
    fun `getTracksForAlbum parses combined disc and track numbers`() {
        val albumId = 123L
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION
            )
        )

        cursor.addRow(arrayOf(1L, "Track 1", 1001, 1000L))
        cursor.addRow(arrayOf(2L, "Track 2", 2002, 2000L))

        `when`(
            mockContentResolver.query(
                eq(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
                any(),
                eq("${MediaStore.Audio.Media.ALBUM_ID} = ?"),
                eq(arrayOf(albumId.toString())),
                eq("${MediaStore.Audio.Media.TRACK} ASC")
            )
        ).thenReturn(cursor)

        val tracks = libraryRepository.getTracksForAlbum(albumId)

        assertEquals(2, tracks.size)

        assertEquals(1L, tracks[0].id)
        assertEquals("Track 1", tracks[0].title)
        assertEquals(1, tracks[0].trackNumber)
        assertEquals(1, tracks[0].discNumber)
        assertEquals(1000L, tracks[0].durationMs)

        assertEquals(2L, tracks[1].id)
        assertEquals("Track 2", tracks[1].title)
        assertEquals(2, tracks[1].trackNumber)
        assertEquals(2, tracks[1].discNumber)
        assertEquals(2000L, tracks[1].durationMs)
    }

    @Test
    fun `getTracksForAlbum handles null fields gracefully`() {
        val albumId = 123L
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION
            )
        )

        cursor.addRow(arrayOf(1L, null, 1, 1000L))

        `when`(
            mockContentResolver.query(
                eq(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
                any(),
                eq("${MediaStore.Audio.Media.ALBUM_ID} = ?"),
                eq(arrayOf(albumId.toString())),
                eq("${MediaStore.Audio.Media.TRACK} ASC")
            )
        ).thenReturn(cursor)

        val tracks = libraryRepository.getTracksForAlbum(albumId)

        assertEquals(1, tracks.size)
        assertEquals("Unknown Track", tracks[0].title)
    }

    @Test
    fun `getTracksForAlbum returns empty list when cursor is null`() {
        val albumId = 123L

        `when`(
            mockContentResolver.query(
                eq(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
                any(),
                eq("${MediaStore.Audio.Media.ALBUM_ID} = ?"),
                eq(arrayOf(albumId.toString())),
                eq("${MediaStore.Audio.Media.TRACK} ASC")
            )
        ).thenReturn(null)

        val tracks = libraryRepository.getTracksForAlbum(albumId)

        assertEquals(0, tracks.size)
    }
}
