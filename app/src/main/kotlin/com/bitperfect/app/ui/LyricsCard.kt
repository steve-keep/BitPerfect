package com.bitperfect.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitperfect.app.player.LrcLine
import com.bitperfect.app.ui.theme.Primary
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Displays synced lyrics as a scrolling list, with the active line centred
 * and highlighted green.
 *
 * Scroll behaviour:
 *  - Active line is always centred when auto-scroll is in control.
 *  - While the user's finger is on-screen (press OR drag), auto-scroll is
 *    suppressed entirely — the list does not move.
 *  - 3 seconds after the finger lifts, auto-scroll resumes and the current
 *    active line snaps smoothly back to centre.
 *  - If the line changes while the user is scrolling freely (finger off,
 *    but within the 3-second grace window) the timer resets so the user
 *    keeps reading without interruption.
 */
@Composable
fun LyricsCard(
    lrcLines: List<LrcLine>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    if (lrcLines.isEmpty()) return

    val listState = rememberLazyListState()

    // ── Active-line index ────────────────────────────────────────────────────
    var activeIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(positionMs, lrcLines) {
        val newIndex = lrcLines.indexOfLast { it.timestampMs <= positionMs }
        if (newIndex != activeIndex) {
            activeIndex = newIndex
        }
    }

    // ── Viewport height (needed to compute the centering offset) ─────────────
    var viewportHeight by remember { mutableStateOf(0) }

    // ── User-interaction state ───────────────────────────────────────────────
    // isDragged: finger is actively moving/flinging the list
    val isDragged by listState.interactionSource.collectIsDraggedAsState()

    // isFingerDown: finger is physically touching the screen (press or drag)
    var isFingerDown by remember { mutableStateOf(false) }

    // userScrolling: true for up to 3 s after the finger lifts — keeps
    // auto-scroll paused while the user reads the lyrics they scrolled to.
    var userScrolling by remember { mutableStateOf(false) }

    // ── Finger-lift → resume-auto-scroll timer ───────────────────────────────
    // Whenever isDragged or isFingerDown transitions to false we start (or
    // restart) the 3-second timer. If either goes true again we just flip
    // userScrolling back on immediately (the LaunchedEffect for those states
    // handles that).
    LaunchedEffect(isDragged, isFingerDown) {
        if (isDragged || isFingerDown) {
            // Finger is on-screen — make sure userScrolling is set and kill
            // any running timer coroutine (this effect restarts on each change).
            userScrolling = true
        } else {
            // Both are false — start the 3-second grace period.
            userScrolling = true
            delay(3_000)
            userScrolling = false
            // Snap back to the active line once the grace period ends.
            scrollToActive(listState, activeIndex, viewportHeight)
        }
    }

    // ── Auto-scroll when the active line changes ─────────────────────────────
    LaunchedEffect(activeIndex) {
        if (!userScrolling && !isFingerDown && activeIndex >= 0) {
            scrollToActive(listState, activeIndex, viewportHeight)
        }
    }

    // ── Fade mask ────────────────────────────────────────────────────────────
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
            // Capture viewport height for centering calculations.
            .onGloballyPositioned { coords ->
                viewportHeight = coords.size.height
            }
            // Detect raw finger press/release so we can suppress auto-scroll
            // even during a slow, non-fling touch.
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isFingerDown = true
                        tryAwaitRelease()
                        isFingerDown = false
                    }
                )
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
            // Large vertical padding so the first and last lines can be
            // centred in the viewport rather than pinned to the edges.
            contentPadding = PaddingValues(vertical = 400.dp)
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
}

/**
 * Smoothly scrolls [listState] so that the item at [activeIndex] is vertically
 * centred within the [viewportHeight]-pixel viewport.
 *
 * Strategy:
 *  1. If the item is already in the visible item list we can compute the exact
 *     pixel distance to the centre and use [androidx.compose.foundation.gestures.animateScrollBy].
 *  2. If the item is off-screen we jump to it with [LazyListState.animateScrollToItem],
 *     using a negative scrollOffset so the item lands at the centre rather than
 *     the top of the viewport.
 */
private suspend fun scrollToActive(
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeIndex: Int,
    viewportHeight: Int
) {
    if (activeIndex < 0 || viewportHeight == 0) return

    val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }

    if (visibleItem != null) {
        // Item is on-screen — scroll by the exact pixel delta to centre it.
        val itemCenter = visibleItem.offset + visibleItem.size / 2
        val delta = itemCenter - viewportHeight / 2
        listState.animateScrollBy(
            value = delta.toFloat(),
            animationSpec = tween(durationMillis = 400)
        )
    } else {
        // Item is off-screen — jump to it, then centre.
        // scrollOffset is the number of pixels *past* the top of the item
        // at which the viewport top should sit, so a negative value of
        // -(viewportHeight/2 - itemHeight/2) centres it.
        // We don't know the exact item height yet, so we use the average
        // visible item height as an approximation.
        val avgItemHeight = listState.layoutInfo.visibleItemsInfo
            .takeIf { it.isNotEmpty() }
            ?.let { items -> items.sumOf { it.size } / items.size }
            ?: 120 // fallback estimate

        val centreOffset =
 -(viewportHeight / 2) + avgItemHeight / 2 +
            listState.layoutInfo.beforeContentPadding

        listState.animateScrollToItem(
            index = activeIndex,
            scrollOffset = centreOffset
        )
    }
}
