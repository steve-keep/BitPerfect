package com.bitperfect.app.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.app.library.TrackInfo
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackServiceTest {

    private lateinit var playbackService: PlaybackService
    private lateinit var mockLibraryRepository: LibraryRepository

    @Before
    fun setup() {
        playbackService = Robolectric.buildService(PlaybackService::class.java).create().get()
        mockLibraryRepository = mock(LibraryRepository::class.java)

        val field = PlaybackService::class.java.getDeclaredField("libraryRepository\$delegate")
        field.isAccessible = true
        field.set(playbackService, lazy { mockLibraryRepository })
    }

    @Test
    fun `onAddMediaItems resolves media items using LibraryRepository`() {
        val trackId = 123L
        val albumId = 456L

        val trackInfo = TrackInfo(
            id = trackId,
            title = "Test Track",
            trackNumber = 5,
            durationMs = 3000L,
            discNumber = 1,
            albumId = albumId
        )

        `when`(mockLibraryRepository.getTrack(trackId)).thenReturn(trackInfo)

        val innerClass = Class.forName("com.bitperfect.app.player.PlaybackService\$BrowseCallback")
        val constructor = innerClass.getDeclaredConstructors()[0]
        constructor.isAccessible = true
        val callback = constructor.newInstance(playbackService)

        val inputMediaItem = MediaItem.Builder().setMediaId(trackId.toString()).build()

        val onAddMediaItemsMethod = callback::class.java.getDeclaredMethods().first { it.name == "onAddMediaItems" }
        onAddMediaItemsMethod.isAccessible = true

        val controllerInfoClass = MediaSession.ControllerInfo::class.java
        // We can just use allocateInstance via sun.misc.Unsafe to bypass constructor
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafe.javaClass.getDeclaredMethod("allocateInstance", Class::class.java)
        val dummyController = allocateInstance.invoke(unsafe, controllerInfoClass)

        @Suppress("UNCHECKED_CAST")
        val future = onAddMediaItemsMethod.invoke(
            callback,
            mock(MediaSession::class.java),
            dummyController,
            listOf(inputMediaItem)
        ) as ListenableFuture<List<MediaItem>>?

        val resultItems = future?.get()

        assertEquals(1, resultItems?.size)
        val resolvedItem = resultItems?.get(0)
        assertEquals(trackId.toString(), resolvedItem?.mediaId)
        assertEquals("Test Track", resolvedItem?.mediaMetadata?.title)
        assertEquals(5, resolvedItem?.mediaMetadata?.trackNumber)
        val expectedArtUri = Uri.parse("content://media/external/audio/albumart/$albumId")
        assertEquals(expectedArtUri, resolvedItem?.mediaMetadata?.artworkUri)
    }
}
