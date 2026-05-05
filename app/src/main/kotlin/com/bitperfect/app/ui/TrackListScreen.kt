package com.bitperfect.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitperfect.app.usb.RipStatus

private fun numberToWord(n: Int): String {
    val words = arrayOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    return if (n in 0..10) words[n] else n.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListScreen(
    viewModel: AppViewModel,
    onShareRipInfo: (trackNumber: Int) -> Unit
) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearTracks()
        }
    }

    val viewState by viewModel.trackListViewState.collectAsState()
    val currentMediaId by viewModel.currentMediaId.collectAsState()
    val ripStates by viewModel.ripStates.collectAsState()

    val isRipping = remember(ripStates) {
        ripStates.isNotEmpty() && !ripStates.values.all {
            it.status == RipStatus.SUCCESS ||
            it.status == RipStatus.WARNING ||
            it.status == RipStatus.ERROR
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val state = viewState
        if (state == null || state.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            val groupedTracks = state.tracks.groupBy { it.discNumber }
            val isMultiDisc = groupedTracks.size > 1

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    AlbumHeader(
                        title = state.title,
                        artistName = state.artistName,
                        coverArtUrl = state.coverArtUrl,
                        trackCount = state.tracks.size,
                        isCdMode = state.isCdMode,
                        isRipping = isRipping,
                        onSaveClick = { viewModel.startRip() },
                        onPlayClick = { viewModel.playAlbum(state.tracks) }
                    )
                }

                groupedTracks.forEach { (discNumber, discTracks) ->
                    if (isMultiDisc) {
                        stickyHeader(key = "disc_$discNumber") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Disk ${numberToWord(discNumber)}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    itemsIndexed(discTracks, key = { _, track -> track.id }) { _, track ->
                        val globalIndex = state.tracks.indexOfFirst { it.id == track.id }
                        val isCurrentTrack = track.id.toString() == currentMediaId
                        val tintColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        val titleColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        val ripState = ripStates[track.trackNumber]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !state.isCdMode) {
                                    if (!state.isCdMode) viewModel.playTrack(state.tracks, globalIndex)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.width(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isCdMode && ripState != null && ripState.status != RipStatus.IDLE) {
                                    when (ripState.status) {
                                        RipStatus.RIPPING, RipStatus.VERIFYING -> {
                                            Box(contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(
                                                    progress = { ripState.progress },
                                                    modifier = Modifier.size(32.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    trackColor = Color.DarkGray
                                                )
                                                Text(
                                                    text = "${(ripState.progress * 100).toInt()}%",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                        RipStatus.SUCCESS -> {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Success",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                        RipStatus.WARNING -> {
                                            IconButton(
                                                onClick = { onShareRipInfo(track.trackNumber) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = "AccurateRip verification failed – tap to share details",
                                                    tint = Color(0xFFFFC107),
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        RipStatus.ERROR -> {
                                            Icon(
                                                imageVector = Icons.Default.Error,
                                                contentDescription = "Error",
                                                tint = Color(0xFFF44336),
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                        else -> {}
                                    }
                                } else {
                                    Text(
                                        text = track.trackNumber.toString().padStart(2, '0'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tintColor,
                                        modifier = Modifier.align(Alignment.CenterStart)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = titleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val durationSeconds = track.durationMs / 1000
                                val minutes = durationSeconds / 60
                                val seconds = durationSeconds % 60
                                Text(
                                    text = String.format("%d:%02d", minutes, seconds),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!state.isCdMode) {
                                var showMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "More options",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Play Next") },
                                            onClick = {
                                                viewModel.playNext(track)
                                                showMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Add to Queue") },
                                            onClick = {
                                                viewModel.addToQueue(track)
                                                showMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
    }
}
