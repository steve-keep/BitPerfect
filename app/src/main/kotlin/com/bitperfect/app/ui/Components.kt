package com.bitperfect.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.R
import com.bitperfect.app.library.AlbumInfo
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Clear

@Composable
fun AlbumHeader(
    albumInfo: AlbumInfo?,
    artistName: String,
    trackCount: Int,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit = {}
) {
    var backgroundColor by remember { mutableStateOf(Color(0xFF141414)) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.radialGradient(
                    colors = listOf(backgroundColor.copy(alpha = 0.5f), Color.Transparent),
                    radius = 800f
                )
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(albumInfo?.artUri)
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = albumInfo?.title,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0xFF141414)),
                error = ColorPainter(Color(0xFF141414)),
                onSuccess = { success ->
                    val bitmap = success.result.drawable.toBitmap()
                    Palette.from(bitmap).generate { palette ->
                        palette?.dominantSwatch?.rgb?.let { colorValue ->
                            backgroundColor = Color(colorValue)
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = albumInfo?.title ?: "Unknown Album",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artistName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$trackCount Tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0x99FFFFFF)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth(0.5f).height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Play", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DiscReadyCard(
    toc: DiscToc?,
    discMetadata: DiscMetadata?,
    coverArtUrl: String?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(coverArtUrl)
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = discMetadata?.albumTitle ?: "Album Art",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = R.drawable.app_logo),
                error = painterResource(id = R.drawable.app_logo)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = discMetadata?.albumTitle ?: "Disc Ready",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = discMetadata?.artistName ?: "Looking up metadata…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0x99FFFFFF)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toc?.let { "${it.trackCount} tracks" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0x66FFFFFF)
                )
            }
        }
    }
}

@Composable
fun DeviceList(modifier: Modifier = Modifier, viewModel: AppViewModel) {
    val driveStatus by viewModel.driveStatus.collectAsState()
    val discMetadata by viewModel.discMetadata.collectAsState()
    val coverArtUrl by viewModel.coverArtUrl.collectAsState()

    if (driveStatus is DriveStatus.NoDrive) {
        return
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val currentStatus = driveStatus) {
            is DriveStatus.NoDrive -> { /* Should not be reached, handled above */ }
            is DriveStatus.Connecting -> DriveStatusCard(
                icon = Icons.Outlined.HourglassEmpty,
                headline = "Connecting…",
                subtitle = "Detecting drive capabilities",
                showSpinner = true
            )
            is DriveStatus.PermissionDenied -> DriveStatusCard(
                icon = Icons.Outlined.Lock,
                headline = "Access Denied",
                subtitle = "Re-connect and allow access when prompted"
            )
            is DriveStatus.NotOptical -> DriveStatusCard(
                icon = Icons.Outlined.DeviceUnknown,
                headline = "Unsupported Device",
                subtitle = "Connected device is not a CD drive"
            )
            is DriveStatus.Empty -> DriveStatusCard(
                icon = Icons.Outlined.Album,
                headline = "No Disc Inserted",
                subtitle = "Insert a CD to continue"
            )
            is DriveStatus.DiscReady -> {
                DiscReadyCard(
                    toc = currentStatus.toc,
                    discMetadata = discMetadata,
                    coverArtUrl = coverArtUrl
                )
            }
            is DriveStatus.Error -> DriveStatusCard(
                icon = Icons.Outlined.ErrorOutline,
                headline = "Drive Error",
                subtitle = currentStatus.message
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibrarySection(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onAlbumClick: (AlbumInfo) -> Unit = {}
) {
    val isConfigured by viewModel.isOutputFolderConfigured.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredArtists by viewModel.filteredArtists.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        if (!isConfigured) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "Set an output folder in Settings to browse your library",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text("Search artists or albums") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.DarkGray
                )
            )

            if (filteredArtists.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No albums found",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    filteredArtists.forEach { artist ->
                        stickyHeader(key = artist.id) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        items(artist.albums.chunked(3)) { rowAlbums ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                for (i in 0 until 3) {
                                    if (i < rowAlbums.size) {
                                        val album = rowAlbums[i]
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onAlbumClick(album) },
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AsyncImage(
                                                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                    .data(album.artUri)
                                                    .crossfade(true)
                                                    .diskCachePolicy(CachePolicy.ENABLED)
                                                    .build(),
                                                contentDescription = album.title,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                placeholder = ColorPainter(Color(0xFF141414)),
                                                error = ColorPainter(Color(0xFF141414))
                                            )
                                            Text(
                                                text = album.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveStatusCard(
    icon: ImageVector,
    headline: String,
    subtitle: String,
    showSpinner: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF141414)
        ),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x14FFFFFF)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = Color(0x99FFFFFF)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0x99FFFFFF)
                )
            }
        }
    }
}
