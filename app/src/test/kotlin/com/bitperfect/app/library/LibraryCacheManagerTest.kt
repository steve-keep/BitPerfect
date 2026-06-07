package com.bitperfect.app.library

import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryCacheManagerTest {

    private lateinit var context: Context
    private lateinit var cacheManager: LibraryCacheManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        cacheManager = LibraryCacheManager(context)
    }

    @Test
    fun testSaveAndLoadCache() {
        val stats = ListeningStats(
            mostListenedArtist = TopArtist("Artist", 100, Uri.parse("content://art")),
            totalTimeListenedMs = 1000L,
            topSongsAllTime = listOf(TopSong("Song 1", "Artist", 50)),
            topSongsThisMonth = listOf(TopSong("Song 2", "Artist", 10)),
            topSongsThisYear = listOf()
        )
        cacheManager.saveCachedLists(emptyList(), emptyList(), emptyList(), stats)

        val loaded = cacheManager.loadCachedLists()
        assertNotNull(loaded)
        assertNotNull(loaded?.listeningStats)
        assertEquals("Artist", loaded?.listeningStats?.mostListenedArtist?.artistName)
        assertEquals(100, loaded?.listeningStats?.mostListenedArtist?.playCount)
        assertEquals(1000L, loaded?.listeningStats?.totalTimeListenedMs)
        assertEquals(1, loaded?.listeningStats?.topSongsAllTime?.size)
        assertEquals("Song 1", loaded?.listeningStats?.topSongsAllTime?.first()?.trackTitle)
    }
}
