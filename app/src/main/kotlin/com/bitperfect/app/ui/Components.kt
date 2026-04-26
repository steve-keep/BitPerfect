package com.bitperfect.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background

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
            is DriveStatus.DiscReady -> DriveStatusCard(
                icon = Icons.Outlined.CheckCircle,
                headline = "Disc Ready",
                subtitle = "${driveStatus.info.vendorId} · ${driveStatus.info.productId}"
            )
            is DriveStatus.Error -> DriveStatusCard(
                icon = Icons.Outlined.ErrorOutline,
                headline = "Drive Error",
                subtitle = driveStatus.message
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3DDC68),
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
                        stickyHeader(key = "header_${artist.id}") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        item(key = "content_${artist.id}") {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
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
                                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
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
