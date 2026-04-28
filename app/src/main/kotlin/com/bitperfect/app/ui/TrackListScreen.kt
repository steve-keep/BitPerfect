package com.bitperfect.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun numberToWord(n: Int): String {
    val words = arrayOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    return if (n in 0..10) words[n] else n.toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackListScreen(
    viewModel: AppViewModel
) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearTracks()
        }
    }

    val tracks by viewModel.tracks.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val albumId by viewModel.selectedAlbumId.collectAsState()

    val currentMediaId by viewModel.currentMediaId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    val currentTrackTitle = remember(tracks, currentMediaId) {
        tracks.find { it.id.toString() == currentMediaId }?.title
    }

    val albumInfo = remember(albumId, artists) {
        if (albumId == null) return@remember null
        var foundAlbum: com.bitperfect.app.library.AlbumInfo? = null
        var foundArtistName = ""
        for (artist in artists) {
            val album = artist.albums.find { it.id == albumId }
            if (album != null) {
                foundAlbum = album
                foundArtistName = artist.name
                break
            }
        }
        if (foundAlbum != null) Pair(foundAlbum, foundArtistName) else null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            NowPlayingBar(
                isPlaying = isPlaying,
                currentTrackTitle = currentTrackTitle,
                onPlayPause = { viewModel.togglePlayPause() },
                onSkipPrev = { viewModel.skipPrev() },
                onSkipNext = { viewModel.skipNext() }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
            } else {
            val groupedTracks = tracks.groupBy { it.discNumber }
            val isMultiDisc = groupedTracks.size > 1

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    AlbumHeader(
                        albumInfo = albumInfo?.first,
                        artistName = albumInfo?.second ?: "Unknown Artist",
                        trackCount = tracks.size
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
                        val globalIndex = tracks.indexOfFirst { it.id == track.id }
                        val isCurrentTrack = track.id.toString() == currentMediaId
                        val tintColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        val titleColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.playTrack(tracks, globalIndex) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = track.trackNumber.toString().padStart(2, '0'),
                                style = MaterialTheme.typography.bodyMedium,
                                color = tintColor,
                                modifier = Modifier.width(32.dp)
                            )
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
                        }
                        HorizontalDivider(color = Color(0x14FFFFFF))
                    }
                }
            }
        }
        }
    }
}
