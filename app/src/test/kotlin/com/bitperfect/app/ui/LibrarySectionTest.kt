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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

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
}
