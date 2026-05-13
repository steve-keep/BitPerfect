package com.bitperfect.app.ui

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(viewModel: AppViewModel, onCollapse: () -> Unit = {}) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val upNextQueue by viewModel.upNextQueue.collectAsState()
    val currentQueueIndex by viewModel.currentQueueIndex.collectAsState()
    val currentTrackTitle by viewModel.currentTrackTitle.collectAsState()
    val currentTrackArtist by viewModel.currentTrackArtist.collectAsState()
    val currentAlbumTitle by viewModel.currentAlbumTitle.collectAsState()
    val currentAlbumArtUri by viewModel.currentAlbumArtUri.collectAsState()

    val currentTrackState = viewModel.currentTrack.collectAsState()
    val currentTrack = currentTrackState.value

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            viewModel.pollPosition()
            delay(500)
        }
    }

    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.Hidden,
            skipHiddenState = false
        )
    )

    BottomSheetScaffold(
        scaffoldState = bottomSheetScaffoldState,
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
                )

                val upcomingItems = if (currentQueueIndex + 1 < upNextQueue.size) {
                    upNextQueue.subList(currentQueueIndex + 1, upNextQueue.size)
                } else {
                    emptyList()
                }

                if (upcomingItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No upcoming tracks", color = Color.Gray)
                    }
                } else {
                    val lazyListState = rememberLazyListState()
                    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                        val fromActualIndex = from.index + currentQueueIndex + 1
                        val toActualIndex = to.index + currentQueueIndex + 1
                        viewModel.moveQueueItem(fromActualIndex, toActualIndex)
                    }

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        itemsIndexed(upcomingItems, key = { _, item -> item.mediaId + "_" + System.identityHashCode(item) }) { index, item ->
                            val currentItemIndex by androidx.compose.runtime.rememberUpdatedState(index)
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { dismissValue ->
                                    if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                        val actualIndex = currentItemIndex + currentQueueIndex + 1
                                        viewModel.removeQueueItem(actualIndex)
                                        false
                                    } else {
                                        false
                                    }
                                },
                                positionalThreshold = { it * 0.75f }
                            )

                            ReorderableItem(reorderState, key = item.mediaId + "_" + System.identityHashCode(item)) { isDragging ->
                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(vertical = 8.dp)
                                                .background(Color.Red, RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Text(
                                                text = "Delete",
                                                color = Color.White,
                                                modifier = Modifier.padding(end = 16.dp)
                                            )
                                        }
                                    },
                                    content = {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (isDragging) Color(0xFF2A2A2A) else MaterialTheme.colorScheme.surfaceContainerLow)
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val artworkUri = item.mediaMetadata.artworkUri
                                            if (artworkUri != null) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(artworkUri)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = "Album Art",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color(0xFF141414)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Album,
                                                        contentDescription = "No Album Art",
                                                        modifier = Modifier.size(24.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.mediaMetadata.title?.toString() ?: "Unknown",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = Color.Gray,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier.draggableHandle(
                                                    onDragStarted = {},
                                                    onDragStopped = {}
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DragHandle,
                                                    contentDescription = "Reorder",
                                                    tint = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(onClick = { viewModel.clearQueue() }) {
                                    Text(
                                        text = "Clear Queue",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        sheetPeekHeight = 0.dp,
        sheetContainerColor = Color(0xFF1E1E1E),
        containerColor = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = 24.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = 24.dp,
                        start = 24.dp,
                        end = 24.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
            // Top Bar
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Collapse",
                    tint = Color.White
                )
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            // Empty box to keep "Now Playing" centered
            Box(modifier = Modifier.size(48.dp))
        }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                if (currentAlbumArtUri != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(currentAlbumArtUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF141414)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Album,
                            contentDescription = "No Album Art",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = currentTrackTitle ?: "Unknown Track",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        val artistName = currentTrackArtist ?: "Unknown Artist"
        val albumTitle = currentAlbumTitle ?: "Unknown Album"
        Text(
            text = "$artistName · $albumTitle",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF1DB954), // Bright green matching image
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        val durationMs = currentTrack?.durationMs ?: 0L
        val currentPosition = positionMs.coerceAtMost(durationMs)
        val remainingMs = durationMs - currentPosition

        androidx.compose.material3.Slider(
            value = currentPosition.toFloat(),
            valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { viewModel.seekTo(it.toLong()) },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color(0xFF1DB954),
                activeTrackColor = Color(0xFF1DB954),
                inactiveTrackColor = Color.DarkGray
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTimeMs(currentPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "-${formatTimeMs(remainingMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.skipPrev() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }

            Surface(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = Color(0xFF1DB954), // Bright green
                contentColor = Color.Black
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            IconButton(
                onClick = { viewModel.skipNext() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
            }

            // Bottom Up Next Bar
            Spacer(modifier = Modifier.weight(1f))

            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch {
                            bottomSheetScaffoldState.bottomSheetState.expand()
                        }
                    }
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val nextItemTitle = if (currentQueueIndex + 1 < upNextQueue.size) {
                    val nextItem = upNextQueue[currentQueueIndex + 1]
                    "\"${nextItem.mediaMetadata.title}\" by ${nextItem.mediaMetadata.artist}"
                } else {
                    "Nothing playing next"
                }

                Text(
                    text = "Next: $nextItemTitle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
