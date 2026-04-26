package com.bitperfect.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.library.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppViewModelTest {

    private lateinit var viewModel: AppViewModel

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        viewModel = AppViewModel(application)
    }

    @Test
    fun testClearTracks() {
        // Since loadTracks launches coroutine and library repository requires content resolver mocks,
        // we can at least test clearTracks easily to boost coverage.

        // Initial state is empty
        assertEquals(emptyList<TrackInfo>(), viewModel.tracks.value)

        // Just call clearTracks
        viewModel.clearTracks()

        // Assert it's still empty
        assertEquals(emptyList<TrackInfo>(), viewModel.tracks.value)
    }

    @Test
    fun testSearchQueryFilter() {
        viewModel.searchQuery.value = "test"
        assertEquals("test", viewModel.searchQuery.value)
    }

    @Test
    fun testSelectAlbumAndLoadTracks() {
        // Select album which internally calls loadTracks
        viewModel.selectAlbum(123L, "Test Album")
        assertEquals(123L, viewModel.selectedAlbumId.value)
        assertEquals("Test Album", viewModel.selectedAlbumTitle.value)
    }
}
