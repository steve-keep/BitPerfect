package com.bitperfect.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class NowPlayingSliderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testCustomSliderLayoutDoesNotCrash() {
        composeTestRule.setContent {
            Slider(
                value = 50f,
                valueRange = 0f..100f,
                onValueChange = {},
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        modifier = Modifier.size(12.dp)
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(2.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            )
        }

        // Just ensure it was drawn without crashing
        // Compose test nodes for sliders don't easily expose colors/modifiers directly
        // but we can verify it exists as a range element
        composeTestRule.onNode(androidx.compose.ui.test.hasProgressBarRangeInfo(
            androidx.compose.ui.semantics.ProgressBarRangeInfo(current = 50f, range = 0f..100f, steps = 0)
        )).assertExists()
    }
}
