package com.bitperfect.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitperfect.app.player.LrcLine
import com.bitperfect.app.ui.theme.Primary
import kotlin.math.abs

@Composable
fun LyricsCard(
    lrcLines: List<LrcLine>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    if (lrcLines.isEmpty()) return

    val listState = rememberLazyListState()

    var activeIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(positionMs, lrcLines) {
        val newIndex = lrcLines.indexOfLast { it.timestampMs <= positionMs + 500L }
        if (newIndex != activeIndex) {
            activeIndex = newIndex
        }
    }

    var viewportHeight by remember { mutableStateOf(0) }

    // Auto-scroll strictly to the active lyric
    LaunchedEffect(activeIndex, viewportHeight) {
        if (activeIndex >= 0 && viewportHeight > 0) {
            // Animate scroll to item. We want it centered.
            // Using a simple approximation for item height since it varies slightly with font size scaling,
            // but standardizing based on the visible layout helps.
            val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
            val itemHeight = itemInfo?.size ?: 100 // fallback estimate

            // Offset to center the item in the viewport
            // scrollOffset is the offset from the top of the viewport to the top of the item.
            // So if we want the item centered, the offset should be negative.
            // formula: -(viewportHeight / 2) + (itemHeight / 2) + beforeContentPadding
            val centerOffset = -(viewportHeight / 2) + (itemHeight / 2) + listState.layoutInfo.beforeContentPadding

            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = centerOffset
            )
        }
    }

    val fadeBrush = remember {
        Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.12f to Color.Black,
            0.88f to Color.Black,
            1.00f to Color.Transparent
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                viewportHeight = coords.size.height
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                },
            // Use half viewport padding to allow first/last items to be centered naturally
            contentPadding = PaddingValues(
                top = with(androidx.compose.ui.platform.LocalDensity.current) { (viewportHeight / 2).toDp() },
                bottom = with(androidx.compose.ui.platform.LocalDensity.current) { (viewportHeight / 2).toDp() }
            ),
            userScrollEnabled = false // Strictly follows audio, no manual scroll
        ) {
            itemsIndexed(lrcLines) { index, line ->
                val distance = if (activeIndex >= 0) abs(index - activeIndex) else Int.MAX_VALUE

                val targetAlpha = when {
                    index == activeIndex -> 1.00f
                    distance == 1       -> 0.85f
                    distance == 2       -> 0.55f
                    else                -> 0.25f
                }
                val targetColor = if (index == activeIndex) Primary else Color.White

                // Add an animated scale effect for the active lyric
                val targetScale = if (index == activeIndex) 1.1f else 1.0f

                val animatedAlpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = tween(300),
                    label = "lyric_alpha_$index"
                )
                val animatedColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(300),
                    label = "lyric_color_$index"
                )
                val animatedScale by animateFloatAsState(
                    targetValue = targetScale,
                    animationSpec = tween(300),
                    label = "lyric_scale_$index"
                )

                Text(
                    text = line.text,
                    fontSize = 24.sp,
                    fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Medium,
                    color = animatedColor.copy(alpha = animatedAlpha),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 24.dp)
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                        },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}
