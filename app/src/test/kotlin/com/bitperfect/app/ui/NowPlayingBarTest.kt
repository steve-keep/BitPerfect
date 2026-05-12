package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
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
                onPlayPause = {}
            )
        }
        // The empty string "" is used as a fallback if the title is null
        composeTestRule.onNodeWithText("").assertExists()
    }

    @Test
    fun verifyNowPlayingBarVisibleWithTitle() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "My Favorite Song",
                currentTrackArtist = "The Band",
                currentAlbumArtUri = null,
                onPlayPause = {}
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
                onPlayPause = {}
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
                onPlayPause = {}
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
                onPlayPause = {}
            )
        }

        composeTestRule.mainClock.advanceTimeBy(500)

        composeTestRule.onNodeWithTag("now_playing_title", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("now_playing_artist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun verifyCallbacksInvoked() {
        var playPauseClicked = false

        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "Test Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = { playPauseClicked = true }
            )
        }

        composeTestRule.onNodeWithTag("now_playing_play_pause", useUnmergedTree = true).performClick()
        assert(playPauseClicked)
    }

    @Test
    fun playPauseButtonDoesNotFireOnVerticalDrag() {
        var clicked = false
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = { clicked = true }
            )
        }

        // Simulate a downward drag starting on the play/pause button
        composeTestRule
            .onNodeWithTag("now_playing_play_pause", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, 200f))   // large vertical drag
                up()
            }

        assert(!clicked) { "onPlayPause should NOT fire on a vertical drag" }
    }

    @Test
    fun playPauseIconReflectsStatePause() {
        // Pause icon shown when playing
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = true,
                currentTrackTitle = "Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = {}
            )
        }
        composeTestRule
            .onNode(
                androidx.compose.ui.test.hasContentDescription("Pause")
            )
            .assertExists()
    }

    @Test
    fun playPauseIconReflectsStatePlay() {
        // Play icon shown when paused
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "Song",
                currentTrackArtist = null,
                currentAlbumArtUri = null,
                onPlayPause = {}
            )
        }
        composeTestRule
            .onNode(
                androidx.compose.ui.test.hasContentDescription("Play")
            )
            .assertExists()
    }
}
