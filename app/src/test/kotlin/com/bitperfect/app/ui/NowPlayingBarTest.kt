package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifyNowPlayingBarHiddenWhenNoTitle() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = null,
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = {},
                onClick = {}
            )
        }
        composeTestRule.onNodeWithTag("now_playing_title").assertDoesNotExist()
    }

    @Test
    fun verifyNowPlayingBarVisibleWithTitle() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "My Favorite Song",
                currentTrackArtist = "The Band",
                currentAlbumArtUri = null,
                onPlayPause = {},
                onClick = {}
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithTag("now_playing_title", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("My Favorite Song", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("now_playing_artist", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("The Band", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun verifyNowPlayingBarWithArtUri() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = true,
                currentTrackTitle = "My Favorite Song",
                currentTrackArtist = "The Band",
                currentAlbumArtUri = android.net.Uri.parse("content://media/external/audio/albumart/1"),
                onPlayPause = {},
                onClick = {}
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithTag("now_playing_title", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun verifyNowPlayingBarWithoutArtist() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "My Favorite Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = {},
                onClick = {}
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithTag("now_playing_title", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("now_playing_artist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun verifyNowPlayingBarWithEmptyArtist() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "My Favorite Song",
                currentTrackArtist = "",
                currentAlbumArtUri = null,
                onPlayPause = {},
                onClick = {}
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithTag("now_playing_title", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("now_playing_artist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun verifyCallbacksInvoked() {
        var playPauseClicked = false
        var onClickClicked = false

        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "Test Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = { playPauseClicked = true },
                onClick = { onClickClicked = true }
            )
        }

        composeTestRule.onNodeWithTag("now_playing_play_pause", useUnmergedTree = true).performClick()
        assert(playPauseClicked)

        composeTestRule.onNodeWithText("Test Song", useUnmergedTree = true).performClick()
        assert(onClickClicked)
    }
}
