package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitperfect.app.BuildConfig
import com.bitperfect.app.library.ListeningStats
import com.bitperfect.app.library.TopSong
import com.bitperfect.core.services.DriveOffsetRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

@Composable
fun AboutScreen(
    driveOffsetRepository: DriveOffsetRepository,
    viewModel: AppViewModel,
    onNavigateToAlbum: (Long, String) -> Unit = { _, _ -> }
) {
    val generatedAt by driveOffsetRepository.generatedAt.collectAsState()
    val stats by viewModel.listeningStats.collectAsState()

    val relativeTimeText = remember(generatedAt) {
        if (generatedAt == null) {
            "offset not downloaded"
        } else {
            try {
                val instant = Instant.parse(generatedAt)
                val now = Instant.now()
                val days = ChronoUnit.DAYS.between(instant, now)
                if (days == 0L) {
                    "offset downloaded today"
                } else if (days == 1L) {
                    "offset downloaded 1 day ago"
                } else {
                    "offset downloaded $days days ago"
                }
            } catch (e: DateTimeParseException) {
                "offset downloaded at $generatedAt"
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (stats != null) {
            val statsVal = stats!!

            if (statsVal.mostListenedArtist != null) {
                item {
                    StatCard(title = "Most Listened Artist", icon = Icons.Default.MusicNote) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (statsVal.mostListenedArtist.artUri != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.size(64.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(statsVal.mostListenedArtist.artUri)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            Column {
                                Text(
                                    text = statsVal.mostListenedArtist.artistName,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${statsVal.mostListenedArtist.playCount} plays",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                val totalHours = statsVal.totalTimeListenedMs / (1000 * 60 * 60)
                val totalMinutes = (statsVal.totalTimeListenedMs / (1000 * 60)) % 60
                val timeString = "${totalHours}h ${totalMinutes}m"
                StatCard(title = "Total Time Listened", icon = Icons.Default.Schedule) {
                    Column {
                        Text(
                            text = timeString,
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "All time listening",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (statsVal.topSongsAllTime.isNotEmpty()) {
                item {
                    StatCard(title = "Top 5 Songs", icon = Icons.Default.TrendingUp) {
                        SongList(songs = statsVal.topSongsAllTime, onNavigateToAlbum = onNavigateToAlbum)
                    }
                }
            }

            if (statsVal.topSongsThisMonth.isNotEmpty()) {
                item {
                    StatCard(title = "Most Listened This Month", icon = Icons.Default.CalendarToday) {
                        SongList(songs = statsVal.topSongsThisMonth, onNavigateToAlbum = onNavigateToAlbum)
                    }
                }
            }

            if (statsVal.topSongsThisYear.isNotEmpty()) {
                item {
                    StatCard(title = "Most Listened This Year", icon = Icons.Default.CalendarToday) {
                        SongList(songs = statsVal.topSongsThisYear, onNavigateToAlbum = onNavigateToAlbum)
                    }
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "offset downloaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = relativeTimeText.replace("offset downloaded ", ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun SongList(songs: List<TopSong>, onNavigateToAlbum: (Long, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        songs.forEachIndexed { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = song.albumId != -1L) {
                        onNavigateToAlbum(song.albumId, song.albumTitle)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${song.trackTitle}",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = song.playCount.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
