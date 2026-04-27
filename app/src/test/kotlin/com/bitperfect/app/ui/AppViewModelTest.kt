package com.bitperfect.app.ui

import android.app.Application
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.player.PlayerRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppViewModelTest {

    private lateinit var viewModel: AppViewModel
    private lateinit var mockRepository: PlayerRepository

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        mockRepository = mock(PlayerRepository::class.java)

        org.mockito.Mockito.`when`(mockRepository.isPlaying).thenReturn(MutableStateFlow(false))
        org.mockito.Mockito.`when`(mockRepository.currentMediaId).thenReturn(MutableStateFlow(null))
        org.mockito.Mockito.`when`(mockRepository.positionMs).thenReturn(MutableStateFlow(0L))

        viewModel = AppViewModel(application, mockRepository)
    }

    @Test
    fun testCurrentTrackTitleResolution() = runTest {
        val tracks = listOf(
            TrackInfo(1L, "First Song", 1, 1000L),
            TrackInfo(2L, "Second Song", 2, 2000L)
        )

        // Use a test-specific mock repository to allow mutating currentMediaId
        val mutableCurrentMediaId = MutableStateFlow<String?>(null)
        org.mockito.Mockito.`when`(mockRepository.currentMediaId).thenReturn(mutableCurrentMediaId)

        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(application, mockRepository)

        // Start collecting the currentTrackTitle stateflow so that it activates and stays alive
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.currentTrackTitle.collect {}
        }

        vm.playAlbum(tracks)
        mutableCurrentMediaId.value = "1"
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("First Song", vm.currentTrackTitle.value)

        mutableCurrentMediaId.value = "2"
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("Second Song", vm.currentTrackTitle.value)

        mutableCurrentMediaId.value = "3"
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(null, vm.currentTrackTitle.value)

        job.cancel()
    }

    @Test
    fun testClearTracks() {
        assertEquals(emptyList<TrackInfo>(), viewModel.tracks.value)
        viewModel.clearTracks()
        assertEquals(emptyList<TrackInfo>(), viewModel.tracks.value)
    }

    @Test
    fun testSearchQueryFilter() {
        viewModel.searchQuery.value = "test"
        assertEquals("test", viewModel.searchQuery.value)
    }

    @Test
    fun testSelectAlbumAndLoadTracks() {
        viewModel.selectAlbum(123L, "Test Album")
        assertEquals(123L, viewModel.selectedAlbumId.value)
        assertEquals("Test Album", viewModel.selectedAlbumTitle.value)
    }

    @Test
    fun testPlaybackDelegates() {
        val tracks = listOf(TrackInfo(1L, "Test", 1, 1000L))

        viewModel.playAlbum(tracks)
        verify(mockRepository).playAlbum(tracks)

        viewModel.playTrack(tracks, 0)
        verify(mockRepository).playTrack(tracks, 0)

        viewModel.togglePlayPause()
        verify(mockRepository).togglePlayPause()

        viewModel.seekTo(500L)
        verify(mockRepository).seekTo(500L)

        viewModel.skipNext()
        verify(mockRepository).skipNext()

        viewModel.skipPrev()
        verify(mockRepository).skipPrev()
    }
}
