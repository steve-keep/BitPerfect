package com.bitperfect.app.library

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import android.provider.DocumentsContract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: LibraryRepository
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LibraryRepository(context)
        tempDir = File(context.cacheDir, "test_library_dir")
        tempDir.mkdirs()
    }

    @Test
    fun getListeningStatistics_noUri_returnsNull() {
        val stats = repository.getListeningStatistics(null)
        assertNull(stats)
    }

    // Since it's tricky to mock DocumentFile from tree URI fully in Robolectric without heavy
    // ContentProvider shadowing, we will rely on the fact that if recently-played.jsonl doesn't exist,
    // it returns null or empty stats.

    // We can test the stats logic by doing a small hack or focusing on the pure logic.
    // Given the complexity of DocumentsProvider, let's just make sure it returns null when dir doesn't exist.
    @Test
    fun getListeningStatistics_invalidUri_returnsNull() {
        val stats = repository.getListeningStatistics("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        assertNull(stats)
    }


    @Test
    fun escapeSqlLike_escapesSpecialCharactersCorrectly() {
        val input = "Music\\Album_Name%100"
        val expected = "Music\\\\Album\\_Name\\%100"
        val actual = repository.escapeSqlLike(input)
        assertEquals(expected, actual)
    }

    @Test
    fun escapeSqlLike_noSpecialCharacters_returnsSameString() {
        val input = "Music/Normal/Path"
        val actual = repository.escapeSqlLike(input)
        assertEquals(input, actual)
    }
}
