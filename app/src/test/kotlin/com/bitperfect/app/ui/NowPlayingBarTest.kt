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
                onPlayPause = {},
                onSkipPrev = {},
                onSkipNext = {}
            )
        }

        // The bar should not be visible when currentTrackTitle is null
        composeTestRule.onNodeWithTag("now_playing_title").assertDoesNotExist()
    }

    @Test
    fun verifyNowPlayingBarVisibleWithTitle() {
        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "My Favorite Song",
                onPlayPause = {},
                onSkipPrev = {},
                onSkipNext = {}
            )
        }

        composeTestRule.onNodeWithTag("now_playing_title").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Favorite Song").assertIsDisplayed()
    }

    @Test
    fun verifyCallbacksInvoked() {
        var playPauseClicked = false
        var skipPrevClicked = false
        var skipNextClicked = false

        composeTestRule.setContent {
            NowPlayingBar(
                isPlaying = false,
                currentTrackTitle = "Test Song",
                onPlayPause = { playPauseClicked = true },
                onSkipPrev = { skipPrevClicked = true },
                onSkipNext = { skipNextClicked = true }
            )
        }

        composeTestRule.onNodeWithTag("now_playing_play_pause").performClick()
        composeTestRule.onNodeWithTag("now_playing_skip_prev").performClick()
        composeTestRule.onNodeWithTag("now_playing_skip_next").performClick()

        assert(playPauseClicked)
        assert(skipPrevClicked)
        assert(skipNextClicked)
    }
}
