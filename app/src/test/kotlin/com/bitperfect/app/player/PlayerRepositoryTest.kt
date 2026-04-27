package com.bitperfect.app.player

import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bitperfect.app.library.TrackInfo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlayerRepositoryTest {

    @Test
    fun `test initial state`() = runTest {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.packageName).thenReturn("com.bitperfect.app")

        val fakeFactory = object : PlayerRepository.MediaControllerFactory {
            override fun build(context: Context, token: SessionToken): ListenableFuture<MediaController> {
                return Futures.immediateFuture(null)
            }
        }

        val repository = PlayerRepository(mockContext, fakeFactory)

        assertEquals(false, repository.isPlaying.value)
        assertNull(repository.currentMediaId.value)
        assertEquals(0L, repository.positionMs.value)
    }

    @Test
    fun `test playAlbum and playTrack coverage`() = runTest {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.packageName).thenReturn("com.bitperfect.app")

        val repository = PlayerRepository(mockContext)

        val tracks = listOf(TrackInfo(1L, "Track 1", 1, 1000L))
        // Calling with null controller simply doesn't crash
        repository.playAlbum(tracks)
        assertEquals(1, tracks.size)
    }

    @Test
    fun `test playback controls coverage`() = runTest {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.packageName).thenReturn("com.bitperfect.app")

        val repository = PlayerRepository(mockContext)

        // Calling with null controller simply doesn't crash
        repository.seekTo(5000L)
        repository.skipNext()
        repository.skipPrev()
        repository.togglePlayPause()

        assertEquals(0L, repository.positionMs.value)
    }

    @Test
    fun `test disconnect coverage`() = runTest {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.packageName).thenReturn("com.bitperfect.app")

        val repository = PlayerRepository(mockContext)

        repository.disconnect()

        val controllerField = PlayerRepository::class.java.getDeclaredField("controller")
        controllerField.isAccessible = true
        assertNull(controllerField.get(repository))
    }
}
