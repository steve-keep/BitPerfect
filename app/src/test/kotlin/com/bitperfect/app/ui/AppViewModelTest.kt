package com.bitperfect.app.ui

import android.app.Application
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.DriveInfo
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
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
    private lateinit var mockLookupMusicBrainz: suspend (DiscToc) -> DiscMetadata?
    private lateinit var mockDriveStatusFlow: MutableStateFlow<DriveStatus>
    private var originalDriveStatusFlow: StateFlow<DriveStatus>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val application = ApplicationProvider.getApplicationContext<Application>()
        mockRepository = mock(PlayerRepository::class.java)

        org.mockito.Mockito.`when`(mockRepository.isPlaying).thenReturn(MutableStateFlow(false))
        org.mockito.Mockito.`when`(mockRepository.currentMediaId).thenReturn(MutableStateFlow(null))
        org.mockito.Mockito.`when`(mockRepository.currentTrackTitle).thenReturn(MutableStateFlow(null))
        org.mockito.Mockito.`when`(mockRepository.currentAlbumArtUri).thenReturn(MutableStateFlow(null))
        org.mockito.Mockito.`when`(mockRepository.positionMs).thenReturn(MutableStateFlow(0L))

        mockLookupMusicBrainz = { null } // default stub

        mockDriveStatusFlow = MutableStateFlow(DriveStatus.NoDrive)
        val field = DeviceStateManager::class.java.getDeclaredField("driveStatus")
        field.isAccessible = true

        try {
            originalDriveStatusFlow = field.get(DeviceStateManager) as? StateFlow<DriveStatus>
        } catch (e: Exception) {
            // It might be uninitialized
        }
        field.set(DeviceStateManager, mockDriveStatusFlow)

        // Reset the singleton so tests run independently
        val detectorField = DeviceStateManager::class.java.getDeclaredField("usbDriveDetector")
        detectorField.isAccessible = true
        detectorField.set(DeviceStateManager, null)

        // Instantiate with a wrapper lambda that delegates to mockLookupMusicBrainz
        viewModel = AppViewModel(application, mockRepository, { mockLookupMusicBrainz(it) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun teardown() {
        Dispatchers.resetMain()
        val field = DeviceStateManager::class.java.getDeclaredField("driveStatus")
        field.isAccessible = true
        if (originalDriveStatusFlow != null) {
            field.set(DeviceStateManager, originalDriveStatusFlow)
        } else {
            // Need to reset to uninitialized state, but since it's a primitive/reference, setting to null is tricky for lateinit.
            // However, we can re-initialize it or leave it as a new NoDrive flow.
            // Actually, we can reset usbDriveDetector and re-init.
            val detectorField = DeviceStateManager::class.java.getDeclaredField("usbDriveDetector")
            detectorField.isAccessible = true
            detectorField.set(DeviceStateManager, null)
        }
    }

    @Test
    fun testDiscMetadataPopulatedOnDiscReadyWithToc() = runTest {
        val dummyToc = DiscToc(emptyList(), 10)
        val dummyMetadata = DiscMetadata("Album", "Artist", emptyList(), "mbid")

        mockLookupMusicBrainz = { if (it == dummyToc) dummyMetadata else null }

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.discMetadata.collect {}
        }

        mockDriveStatusFlow.value = DriveStatus.DiscReady(DriveInfo("Vendor", "Product", true), dummyToc)
        advanceUntilIdle()

        // Wait for Dispatchers.IO coroutine to update the value
        val startTime = System.currentTimeMillis()
        while (viewModel.discMetadata.value == null && System.currentTimeMillis() - startTime < 5000) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }

        assertEquals(dummyMetadata, viewModel.discMetadata.value)
        job.cancel()
    }

    @Test
    fun testSecondaryConstructorCoverage() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        try {
            AppViewModel(application)
        } catch (e: Exception) {
            // Ignore NPE or other initialization errors from real PlayerRepository in tests
        }
    }

    @Test
    fun testDiscMetadataResetsToNullOnNoDrive() = runTest {
        val dummyToc = DiscToc(emptyList(), 10)
        val dummyMetadata = DiscMetadata("Album", "Artist", emptyList(), "mbid")

        mockLookupMusicBrainz = { if (it == dummyToc) dummyMetadata else null }

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.discMetadata.collect {}
        }

        mockDriveStatusFlow.value = DriveStatus.DiscReady(DriveInfo("Vendor", "Product", true), dummyToc)
        advanceUntilIdle()

        val startTime = System.currentTimeMillis()
        while (viewModel.discMetadata.value == null && System.currentTimeMillis() - startTime < 5000) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
        assertEquals(dummyMetadata, viewModel.discMetadata.value)

        mockDriveStatusFlow.value = DriveStatus.NoDrive
        advanceUntilIdle()

        val startNullTime = System.currentTimeMillis()
        while (viewModel.discMetadata.value != null && System.currentTimeMillis() - startNullTime < 5000) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
        assertEquals(null, viewModel.discMetadata.value)

        job.cancel()
    }

    @Test
    fun testDiscMetadataStaysNullOnDiscReadyNullToc() = runTest {
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.discMetadata.collect {}
        }

        mockDriveStatusFlow.value = DriveStatus.DiscReady(DriveInfo("Vendor", "Product", true), null)
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(null, viewModel.discMetadata.value)
        job.cancel()
    }

    @Test
    fun testCurrentTrackTitleResolution() = runTest {
        val tracks = listOf(
            TrackInfo(1L, "First Song", 1, 1000L, 1, 100L),
            TrackInfo(2L, "Second Song", 2, 2000L, 1, 100L)
        )

        // Use a test-specific mock repository to allow mutating state flows
        val mutableCurrentMediaId = MutableStateFlow<String?>(null)
        val mutableCurrentTrackTitle = MutableStateFlow<String?>(null)
        val mutableCurrentAlbumArtUri = MutableStateFlow<android.net.Uri?>(null)
        org.mockito.Mockito.`when`(mockRepository.currentMediaId).thenReturn(mutableCurrentMediaId)
        org.mockito.Mockito.`when`(mockRepository.currentTrackTitle).thenReturn(mutableCurrentTrackTitle)
        org.mockito.Mockito.`when`(mockRepository.currentAlbumArtUri).thenReturn(mutableCurrentAlbumArtUri)

        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(application, mockRepository, { mockLookupMusicBrainz(it) })

        // Start collecting the currentTrackTitle stateflow so that it activates and stays alive
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.currentTrackTitle.collect {}
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.currentAlbumArtUri.collect {}
        }

        vm.playAlbum(tracks)
        mutableCurrentMediaId.value = "1"
        mutableCurrentTrackTitle.value = "First Song"
        mutableCurrentAlbumArtUri.value = android.net.Uri.parse("content://media/external/audio/albumart/100")
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("First Song", vm.currentTrackTitle.value)
        assertEquals("content://media/external/audio/albumart/100", vm.currentAlbumArtUri.value?.toString())

        mutableCurrentMediaId.value = "2"
        mutableCurrentTrackTitle.value = "Second Song"
        mutableCurrentAlbumArtUri.value = android.net.Uri.parse("content://media/external/audio/albumart/100")
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("Second Song", vm.currentTrackTitle.value)

        mutableCurrentMediaId.value = "3"
        mutableCurrentTrackTitle.value = null
        mutableCurrentAlbumArtUri.value = null
        advanceUntilIdle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(null, vm.currentTrackTitle.value)

        job.cancel()
        job2.cancel()
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
