package com.bitperfect.app.output

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.app.BitPerfectApplication
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.core.output.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BitPerfectApplication::class)
class OutputRepositoryTest {

    @Mock
    private lateinit var mockPlayerRepository: PlayerRepository

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        // Mock required flow from PlayerRepository
        `when`(mockPlayerRepository.isPlaying).thenReturn(MutableStateFlow(false))
    }

    private fun createOutputRepository(scope: CoroutineScope): OutputRepository {
        val context = ApplicationProvider.getApplicationContext<BitPerfectApplication>()
        return OutputRepository(
            context = context,
            playerRepository = mockPlayerRepository,
            scope = scope
        )
    }

    @Test
    fun `takeOverAndPlay delegates to playerRepository playTrack`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val tracks = listOf<TrackInfo>()
        val startIndex = 0

        outputRepository.takeOverAndPlay(tracks, startIndex)

        verify(mockPlayerRepository).playTrack(tracks, startIndex)
    }

    @Test
    fun `play delegates to playerRepository play`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.play()

        verify(mockPlayerRepository).play()
    }

    @Test
    fun `pause delegates to playerRepository pause`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.pause()

        verify(mockPlayerRepository).pause()
    }

    @Test
    fun `togglePlayPause delegates to playerRepository togglePlayPause`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.togglePlayPause()

        verify(mockPlayerRepository).togglePlayPause()
    }

    @Test
    fun `seekTo delegates to playerRepository seekTo`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val position = 1000L
        outputRepository.seekTo(position)

        verify(mockPlayerRepository).seekTo(position)
    }

    @Test
    fun `getPositionMs delegates to playerRepository positionMs`() = runTest {
        val expectedPosition = 1234L
        val mockStateFlow = MutableStateFlow(expectedPosition)
        `when`(mockPlayerRepository.positionMs).thenReturn(mockStateFlow)

        val outputRepository = createOutputRepository(backgroundScope)
        val position = outputRepository.getPositionMs()

        assertEquals(expectedPosition, position)
    }

    @Test
    fun `skipNext delegates to playerRepository skipNext`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.skipNext()

        verify(mockPlayerRepository).skipNext()
    }

    @Test
    fun `skipPrev delegates to playerRepository skipPrev`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.skipPrev()

        verify(mockPlayerRepository).skipPrev()
    }

    @Test
    fun `appendToQueue delegates to playerRepository addToQueue`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val track = mock(TrackInfo::class.java)

        outputRepository.appendToQueue(track)

        verify(mockPlayerRepository).addToQueue(track)
    }

    @Test
    fun `appendAlbumToQueue delegates to playerRepository addAlbumToQueue`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val tracks = listOf<TrackInfo>()

        outputRepository.appendAlbumToQueue(tracks)

        verify(mockPlayerRepository).addAlbumToQueue(tracks)
    }

    @Test
    fun `insertNextInQueue delegates to playerRepository playNext`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val track = mock(TrackInfo::class.java)

        outputRepository.insertNextInQueue(track)

        verify(mockPlayerRepository).playNext(track)
    }

    @Test
    fun `insertAlbumNextInQueue delegates to playerRepository playAlbumNext`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val tracks = listOf<TrackInfo>()

        outputRepository.insertAlbumNextInQueue(tracks)

        verify(mockPlayerRepository).playAlbumNext(tracks)
    }

    @Test
    fun `reorderQueue delegates to playerRepository moveMediaItem`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val fromIndex = 1
        val toIndex = 2

        outputRepository.reorderQueue(fromIndex, toIndex)

        verify(mockPlayerRepository).moveMediaItem(fromIndex, toIndex)
    }

    @Test
    fun `removeFromQueue delegates to playerRepository removeMediaItem`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        val index = 1

        outputRepository.removeFromQueue(index)

        verify(mockPlayerRepository).removeMediaItem(index)
    }

    @Test
    fun `setVolume executes without crashing`() = runTest {
        val outputRepository = createOutputRepository(backgroundScope)
        outputRepository.setVolume(50)
        // SetVolume is stubbed, so just verifying it runs cleanly
    }
}
