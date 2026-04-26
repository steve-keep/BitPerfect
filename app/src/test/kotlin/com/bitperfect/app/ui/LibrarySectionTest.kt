package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.library.AlbumInfo
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun verifyEmptyStateDisplaysMusicNoteAndText() {
        val application = org.robolectric.RuntimeEnvironment.getApplication()
        val mockViewModel = HomeViewModel(application)

        val settingsManager = com.bitperfect.core.utils.SettingsManager(application)
        settingsManager.outputFolderUri = "content://dummy"
        mockViewModel.loadLibrary()

        composeTestRule.setContent {
            LibrarySection(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("No albums found").assertIsDisplayed()
    }

    @Test
    fun verifyNonEmptyStateDisplaysArtistsAndAlbums() = runTest {
        val application = org.robolectric.RuntimeEnvironment.getApplication()

        // Ensure folder configured is false so it doesn't run IO thread loads immediately
        val settingsManager = com.bitperfect.core.utils.SettingsManager(application)
        settingsManager.outputFolderUri = null

        val mockViewModel = HomeViewModel(application)

        val albums = listOf(
            AlbumInfo(id = 1L, title = "Test Album", artUri = null)
        )
        val artists = listOf(
            ArtistInfo(id = 1L, name = "Test Artist", albums = albums)
        )

        val artistsFlowField = HomeViewModel::class.java.getDeclaredField("_artists")
        artistsFlowField.isAccessible = true
        val flow = artistsFlowField.get(mockViewModel) as MutableStateFlow<List<ArtistInfo>>
        flow.value = artists

        // Setup output folder so it displays library
        val _isOutputFolderConfiguredField = HomeViewModel::class.java.getDeclaredField("_isOutputFolderConfigured")
        _isOutputFolderConfiguredField.isAccessible = true
        val configuredFlow = _isOutputFolderConfiguredField.get(mockViewModel) as MutableStateFlow<Boolean>
        configuredFlow.value = true

        composeTestRule.setContent {
            LibrarySection(viewModel = mockViewModel)
        }

        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(5000)

        composeTestRule.onNodeWithText("Test Artist").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Album").assertIsDisplayed()
    }
}
