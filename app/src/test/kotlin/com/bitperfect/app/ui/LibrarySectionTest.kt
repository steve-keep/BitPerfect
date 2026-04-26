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
        // Need to change how we verify the empty state since we can't easily mock the final HomeViewModel
        // Without an output folder configured, it shows "Set an output folder in Settings to browse your library"
        // Let's actually set a dummy URI to make it configured, and then since the DB is empty, it will show "No albums found"

        val settingsManager = com.bitperfect.core.utils.SettingsManager(application)
        settingsManager.outputFolderUri = "content://dummy"
        mockViewModel.loadLibrary() // re-evaluate configured status

        composeTestRule.setContent {
            LibrarySection(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("No albums found").assertIsDisplayed()
    }
}
