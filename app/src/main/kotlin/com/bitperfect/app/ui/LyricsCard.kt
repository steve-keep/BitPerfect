package com.bitperfect.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitperfect.app.player.LrcLine
import com.bitperfect.app.ui.theme.Primary
import kotlinx.coroutines.delay
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

    // Find active index based on positionMs
    LaunchedEffect(positionMs, lrcLines) {
        val newIndex = lrcLines.indexOfLast { it.timestampMs <= positionMs }
        if (newIndex != activeIndex) {
            activeIndex = newIndex
        }
    }

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }

    // Detect user dragging to pause auto-scroll
    LaunchedEffect(isDragged) {
        if (isDragged) {
            userScrolling = true
        } else if (userScrolling) {
            delay(3000)
            userScrolling = false

            // Re-center when user stops dragging
            if (activeIndex >= 0) {
                val viewportHeight = listState.layoutInfo.viewportSize.height
                val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                val itemHeight = visibleItem?.size ?: 100 // Estimate if not visible
                val beforeContentPadding = listState.layoutInfo.beforeContentPadding
                val centerOffset = -(viewportHeight / 2) + (itemHeight / 2) + beforeContentPadding
                listState.animateScrollToItem(index = activeIndex, scrollOffset = centerOffset)
            }
        }
    }

    // Auto-scroll logic when line changes
    LaunchedEffect(activeIndex) {
        if (!userScrolling && activeIndex >= 0) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
            val itemHeight = visibleItem?.size ?: 100 // Estimate if not visible
            val beforeContentPadding = listState.layoutInfo.beforeContentPadding
            val centerOffset = -(viewportHeight / 2) + (itemHeight / 2) + beforeContentPadding
            listState.animateScrollToItem(index = activeIndex, scrollOffset = centerOffset)
        }
    }

    val fadeBrush = remember {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.15f to Color.Black,
            0.85f to Color.Black,
            1f to Color.Transparent
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
            },
        contentPadding = PaddingValues(vertical = 400.dp)
    ) {
        itemsIndexed(lrcLines) { index, line ->
            val distance = if (activeIndex >= 0) abs(index - activeIndex) else -1

            val targetAlpha = when {
                index == activeIndex -> 1.0f
                distance == 1 -> 0.85f
                distance == 2 -> 0.55f
                else -> 0.25f
            }

            val targetColor = if (index == activeIndex) Primary else Color.White

            val animatedAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(300),
                label = "alpha_animation"
            )
            val animatedColor by animateColorAsState(
                targetValue = targetColor,
                animationSpec = tween(300),
                label = "color_animation"
            )

            Text(
                text = line.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = animatedColor.copy(alpha = animatedAlpha),
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 24.dp)
            )
        }
    }
}
