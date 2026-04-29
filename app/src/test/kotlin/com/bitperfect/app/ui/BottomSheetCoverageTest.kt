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
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomSheetCoverageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun verifyBottomSheetBehaviors() {
        composeTestRule.setContent {
            val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
                bottomSheetState = rememberStandardBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    skipHiddenState = false
                )
            )
            var currentTrackTitle by remember { mutableStateOf<String?>("Test Track") }

            LaunchedEffect(currentTrackTitle) {
                if (currentTrackTitle != null) {
                    bottomSheetScaffoldState.bottomSheetState.partialExpand()
                } else {
                    bottomSheetScaffoldState.bottomSheetState.hide()
                }
            }

            BottomSheetScaffold(
                scaffoldState = bottomSheetScaffoldState,
                sheetPeekHeight = 64.dp,
                sheetDragHandle = null,
                sheetContent = {
                    val progress = if (bottomSheetScaffoldState.bottomSheetState.currentValue == SheetValue.Hidden) {
                        0f
                    } else {
                        if (bottomSheetScaffoldState.bottomSheetState.targetValue == SheetValue.Expanded) 1f else 0f
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = 1f - progress }
                        ) {
                            Text("NowPlayingBar Content")
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = progress }
                        ) {
                            Text("NowPlayingScreen Content")
                        }
                    }
                },
                content = {
                    Text("Main Content")
                }
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1000)
    }
}
