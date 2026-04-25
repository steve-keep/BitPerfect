package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun DeviceList(modifier: Modifier = Modifier, driveStatus: DriveStatus) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (driveStatus) {
            is DriveStatus.NoDrive -> DriveStatusCard(
                icon = Icons.Outlined.UsbOff,
                headline = "No Drive Connected",
                subtitle = "Connect a USB CD drive via OTG"
            )
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
                val totalSeconds = driveStatus.toc.leadOutLba / 75
                val mm = totalSeconds / 60
                val ss = totalSeconds % 60
                val formattedDuration = String.format("%02d:%02d", mm, ss)
                DriveStatusCard(
                    icon = Icons.Outlined.CheckCircle,
                    headline = "Disc Ready",
                    subtitle = "${driveStatus.info.vendorId} · ${driveStatus.info.productId}\n${driveStatus.toc.tracks.size} tracks · $formattedDuration"
                )
            }
            is DriveStatus.Error -> DriveStatusCard(
                icon = Icons.Outlined.ErrorOutline,
                headline = "Drive Error",
                subtitle = driveStatus.message
            )
        }
    }
}

@Composable
fun LibrarySection(viewModel: HomeViewModel, modifier: Modifier = Modifier) {
    val isConfigured by viewModel.isOutputFolderConfigured.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredArtists by viewModel.filteredArtists.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
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
                singleLine = true
            )

            if (filteredArtists.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "No albums found",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredArtists, key = { it.id }) { artist ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(artist.albums, key = { it.id }) { album ->
                                    Column(
                                        modifier = Modifier.width(80.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        AsyncImage(
                                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                .data(album.artUri)
                                                .crossfade(true)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .build(),
                                            contentDescription = album.title,
                                            modifier = Modifier.size(80.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            placeholder = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo),
                                            error = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo)
                                        )
                                        Text(
                                            text = album.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
