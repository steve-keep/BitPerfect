package com.bitperfect.app.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.library.AlbumInfo
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.collect.ImmutableList
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
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
        // Build the service but do not call create() yet
        val controller = Robolectric.buildService(PlaybackService::class.java)
        playbackService = controller.get()
        mockLibraryRepository = mock(LibraryRepository::class.java)

        // Inject the mock BEFORE onCreate is called to prevent errors inside the real dependencies,
        // but Robolectric might trigger onCreate via create().
        // For Media3 MediaLibraryService, creating multiple sessions in the same process can conflict.
        // We ensure MediaSession.Builder provides unique IDs or we tear down properly.
        val field = PlaybackService::class.java.getDeclaredField("libraryRepository\$delegate")
        field.isAccessible = true
        field.set(playbackService, lazy { mockLibraryRepository })

        controller.create()
    }

    @org.junit.After
    fun tearDown() {
        playbackService.onDestroy()
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

    @Test
    fun `onAddMediaItems resolves album items using LibraryRepository`() {
        val albumId = 456L

        val track1 = TrackInfo(
            id = 1L,
            title = "Track 1",
            trackNumber = 1,
            durationMs = 3000L,
            discNumber = 1,
            albumId = albumId
        )

        val track2 = TrackInfo(
            id = 2L,
            title = "Track 2",
            trackNumber = 2,
            durationMs = 3000L,
            discNumber = 1,
            albumId = albumId
        )

        `when`(mockLibraryRepository.getTracksForAlbum(albumId)).thenReturn(listOf(track1, track2))

        val innerClass = Class.forName("com.bitperfect.app.player.PlaybackService\$BrowseCallback")
        val constructor = innerClass.getDeclaredConstructors()[0]
        constructor.isAccessible = true
        val callback = constructor.newInstance(playbackService)

        val inputMediaItem = MediaItem.Builder().setMediaId("album_$albumId").build()

        val onAddMediaItemsMethod = callback::class.java.getDeclaredMethods().first { it.name == "onAddMediaItems" }
        onAddMediaItemsMethod.isAccessible = true

        val controllerInfoClass = MediaSession.ControllerInfo::class.java
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

        assertEquals(2, resultItems?.size)

        val resolvedItem1 = resultItems?.get(0)
        assertEquals("1", resolvedItem1?.mediaId)
        assertEquals("Track 1", resolvedItem1?.mediaMetadata?.title)

        val resolvedItem2 = resultItems?.get(1)
        assertEquals("2", resolvedItem2?.mediaId)
        assertEquals("Track 2", resolvedItem2?.mediaMetadata?.title)
    }

    @Test
    fun `onGetChildren root returns sorted albums`() {
        val album1 = AlbumInfo(id = 1L, title = "Zebra", artUri = null)
        val album2 = AlbumInfo(id = 2L, title = "Apple", artUri = null)
        val album3 = AlbumInfo(id = 3L, title = "Banana", artUri = null)

        val artist1 = ArtistInfo(id = 100L, name = "Artist B", albums = listOf(album1, album2))
        val artist2 = ArtistInfo(id = 101L, name = "Artist A", albums = listOf(album3))

        // For Kotlin testing with Mockito when the argument might be null
        `when`(mockLibraryRepository.getLibrary(org.mockito.ArgumentMatchers.any())).thenReturn(listOf(artist1, artist2))

        val innerClass = Class.forName("com.bitperfect.app.player.PlaybackService\$BrowseCallback")
        val constructor = innerClass.getDeclaredConstructors()[0]
        constructor.isAccessible = true
        val callback = constructor.newInstance(playbackService)

        val onGetChildrenMethod = callback::class.java.getDeclaredMethods().first { it.name == "onGetChildren" }
        onGetChildrenMethod.isAccessible = true

        val controllerInfoClass = MediaSession.ControllerInfo::class.java
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafe.javaClass.getDeclaredMethod("allocateInstance", Class::class.java)
        val dummyController = allocateInstance.invoke(unsafe, controllerInfoClass)

        // Allocate a dummy session instance without invoking the constructor
        val mediaLibrarySessionClass = MediaLibrarySession::class.java
        val dummySession = allocateInstance.invoke(unsafe, mediaLibrarySessionClass)

        // We can just pass null for the MediaLibrarySession if it's not used in the callback,
        // which it shouldn't be for this test case.
        @Suppress("UNCHECKED_CAST")
        val future = onGetChildrenMethod.invoke(
            callback,
            dummySession,
            dummyController,
            "root",
            0,
            0,
            null
        ) as ListenableFuture<androidx.media3.session.LibraryResult<ImmutableList<MediaItem>>>?

        val result = future?.get()
        val items = result?.value

        assertEquals(2, items?.size)

        assertEquals("recent_albums", items?.get(0)?.mediaId)
        assertEquals("Recently Played", items?.get(0)?.mediaMetadata?.title)
        assertEquals(true, items?.get(0)?.mediaMetadata?.isBrowsable)
        assertEquals(false, items?.get(0)?.mediaMetadata?.isPlayable)

        assertEquals("all_albums", items?.get(1)?.mediaId)
        assertEquals("All Albums", items?.get(1)?.mediaMetadata?.title)
        assertEquals(true, items?.get(1)?.mediaMetadata?.isBrowsable)
        assertEquals(false, items?.get(1)?.mediaMetadata?.isPlayable)
    }
}
