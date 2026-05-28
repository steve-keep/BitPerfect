package com.bitperfect.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.R
import com.bitperfect.app.library.AlbumInfo
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import androidx.compose.ui.graphics.Color
import com.bitperfect.app.ui.theme.TextSecondary
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import coil.compose.SubcomposeAsyncImage
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Eject
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.blur
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign

@OptIn(ExperimentalFoundationApi::class)

private
@Composable
fun <T> Sliderow(
    items: List<T>,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(0.dp),
    itemContent: @Composable (T) -> Unit
) {
    val snappingLayout = remember(state) {
        SnapLayoutInfoProvider(state, SnapPosition.Start)
    }
    val flingBehavior = rememberSnapFlingBehavior(snappingLayout)

    LazyRow(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = horizontalArrangement,
        flingBehavior = flingBehavior
    ) {
        items(items) { item ->
            itemContent(item)
        }
    }
}

@Composable
fun throbbingBackgroundModifier(): Modifier {
    val infiniteTransition = rememberInfiniteTransition()
    val color by infiniteTransition.animateColor(
        initialValue = Color(0xFF141414),
        targetValue = Color(0xFF2A2A2A),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "throbbing color"
    )
    return Modifier.background(color)
}

@Composable
fun AlbumHeader(
    onSaveClick: () -> Unit = {},
    title: String,
    artistName: String,
    coverArtUrl: String?,
    trackCount: Int,
    isCdMode: Boolean = false,
    isRipping: Boolean = false,
    overallProgress: Float = 0f,
    isFullyVerified: Boolean = false,
    isAlbumPlaying: Boolean = false,
    isAwaitingEject: Boolean = false,
    onEjectClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    onArtistClick: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    onStopRipClick: () -> Unit = {},
    dominantColor: Color = Color(0xFF141414),
    onColorExtracted: (Color) -> Unit = {},
    primaryColor: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.radialGradient(
                    colors = listOf(dominantColor.copy(alpha = 0.5f), Color.Transparent),
                    radius = 800f
                )
            )
            .padding(
                top = 16.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 16.dp,
                start = 16.dp,
                end = 16.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(coverArtUrl)
                    .allowHardware(false)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                placeholder = ColorPainter(Color(0xFF141414)),
                error = ColorPainter(Color(0xFF141414)),
                onSuccess = { success ->
                    val bitmap = success.result.drawable.toBitmap()
                    Palette.from(bitmap)
                        .addFilter { _, hsl -> hsl[2] in 0.2f..0.85f }
                        .generate { palette ->
                            palette?.let { p ->
                                val swatch = p.vibrantSwatch
                                    ?: p.dominantSwatch
                                    ?: p.mutedSwatch
                                    ?: p.lightMutedSwatch
                                swatch?.rgb?.let { colorValue ->
                                    onColorExtracted(Color(colorValue))
                                }
                            }
                        }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
            val verifiedIconId = "verified_icon"
            val textToDisplay = buildAnnotatedString {
                append(title)
                if (isFullyVerified && !isCdMode) {
                    append(" ")
                    appendInlineContent(verifiedIconId, "[icon]")
                }
            }

            val inlineContent = mapOf(
                Pair(
                    verifiedIconId,
                    InlineTextContent(
                        Placeholder(
                            width = 24.sp,
                            height = 24.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "AccurateRip Verified",
                            tint = com.bitperfect.app.ui.theme.VerificationGreen,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = textToDisplay,
                    inlineContent = inlineContent,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artistName,
                style = MaterialTheme.typography.titleMedium,
                color = primaryColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.clickable { onArtistClick() }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$trackCount Tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0x99FFFFFF),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            if (isRipping) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = primaryColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onStopRipClick) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Stop Rip",
                            tint = Color.White
                        )
                    }
                }
            } else if (isAwaitingEject) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Eject disc to save to library",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFC107)
                        )
                    }
                    IconButton(onClick = onEjectClick) {
                        Icon(
                            imageVector = Icons.Default.Eject,
                            contentDescription = "Eject disc",
                            tint = Color.White
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = if (isCdMode) onSaveClick else onPlayClick,
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                color = primaryColor,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        if (isCdMode) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Save Disc",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            Icon(
                                if (isAlbumPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isAlbumPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RipProgressContent(
    bannerState: RipBannerState,
    onStopRipClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork thumbnail
        if (bannerState.artworkBytes != null) {
            Image(
                bitmap = BitmapFactory.decodeByteArray(
                    bannerState.artworkBytes, 0, bannerState.artworkBytes.size
                ).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Album,
                    contentDescription = null
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Text and progress
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "Ripping ${bannerState.completedTracks} of ${bannerState.totalTracks} tracks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            LinearProgressIndicator(
                progress = { bannerState.overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = Color(0xFF3DDC68),
                trackColor = Color(0xFF2A2A2A)
            )
            Text(
                text = bannerState.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0x99FFFFFF)
            )
            Text(
                text = bannerState.totalTracksLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0x66FFFFFF)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onStopRipClick) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = "Stop Rip",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun DiscReadyCard(
    toc: DiscToc?,
    discMetadata: DiscMetadata?,
    coverArtUrl: String?,
    isKeyDisc: Boolean = false,
    onClick: () -> Unit = {},
    onEjectClick: () -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubcomposeAsyncImage(
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
                loading = {
                    Box(modifier = Modifier.fillMaxSize().then(throbbingBackgroundModifier()))
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize().then(throbbingBackgroundModifier()))
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = discMetadata?.albumTitle ?: "Disc Ready",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isKeyDisc) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "AccurateRip Verified",
                            tint = Color(0xFF3DDC68)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = discMetadata?.artistName ?: "Looking up metadata…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0x99FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toc?.let { "${it.trackCount} tracks" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0x66FFFFFF)
                )
            }
            IconButton(onClick = onEjectClick) {
                Icon(
                    imageVector = Icons.Default.Eject,
                    contentDescription = "Eject Disc",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun DeviceList(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel,
    bannerState: RipBannerState,
    onNavigateToTrackList: () -> Unit = {},
    onViewCd: () -> Unit = {}
) {
    val driveStatus by viewModel.driveStatus.collectAsState()
    val discMetadata by viewModel.discMetadata.collectAsState()
    val coverArtUrl by viewModel.coverArtUrl.collectAsState()
    val isKeyDisc by viewModel.isKeyDisc.collectAsState()

    if (driveStatus !is DriveStatus.NoDrive) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            if (bannerState.isVisible) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF2A2A2A)),
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.viewCdTracks()
                        onNavigateToTrackList()
                    }
                ) {
                    RipProgressContent(
                        bannerState = bannerState,
                        onStopRipClick = {
                            viewModel.cancelRip(true)
                            viewModel.ejectDrive()
                        }
                    )
                }
            } else {
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
                is DriveStatus.SpinningUp -> DriveStatusCard(
                    icon = Icons.Outlined.HourglassBottom,
                    headline = "Spinning Up…",
                    subtitle = "Reading disc information",
                    showSpinner = true
                )
                is DriveStatus.DetectingDisc -> DriveStatusCard(
                    icon = Icons.Outlined.HourglassBottom,
                    headline = "Detecting Disc…",
                    subtitle = "Reading disc information",
                    showSpinner = true
                )
                is DriveStatus.Ejecting -> DriveStatusCard(
                    icon = Icons.Default.Eject,
                    headline = "Ejecting…",
                    subtitle = "Opening disc tray",
                    showSpinner = true
                )
                is DriveStatus.DiscReady -> {
                    DiscReadyCard(
                        toc = currentStatus.toc,
                        discMetadata = discMetadata,
                        coverArtUrl = coverArtUrl,
                        isKeyDisc = isKeyDisc,
                        onClick = {
                            viewModel.viewCdTracks()
                            onViewCd()
                        },
                        onEjectClick = {
                            viewModel.ejectDrive()
                        }
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibrarySection(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onAlbumClick: (AlbumInfo) -> Unit = {},
    onArtistClick: (String) -> Unit = {}
) {
    val isConfigured by viewModel.isOutputFolderConfigured.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredArtists by viewModel.filteredArtists.collectAsState()
    val recentlyPlayedAlbums by viewModel.recentlyPlayedAlbums.collectAsState()
    val rediscoverAlbums by viewModel.rediscoverAlbums.collectAsState()
    val latestRippedAlbums by viewModel.latestRippedAlbums.collectAsState()

    val focusManager = LocalFocusManager.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    Column(
        modifier = modifier
            .fillMaxSize()
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 0.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.searchQuery.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                }

                if (filteredArtists.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(horizontal = 16.dp),
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
                    }
                } else {
                    if (searchQuery.isBlank() && latestRippedAlbums.isNotEmpty()) {
                        item {
                            val albumWidth = (screenWidth - 72.dp) / 3.5f
                            val itemWidth = (albumWidth * 3) + 32.dp // Width of 3 albums + 2 gaps (16.dp each)

                            Sliderow(
                                items = latestRippedAlbums,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) { (artist, album) ->
                                Box(
                                    modifier = Modifier
                                        .width(itemWidth)
                                        .aspectRatio(2.2f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onAlbumClick(album) }
                                ) {
                                    // Blurred background
                                    AsyncImage(
                                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                            .data(album.artUri)
                                            .crossfade(true)
                                            .diskCachePolicy(CachePolicy.ENABLED)
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .blur(20.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        placeholder = ColorPainter(Color(0xFF141414)),
                                        error = ColorPainter(Color(0xFF141414))
                                    )

                                    // Dark overlay to ensure text readability
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .drawBehind {
                                                drawRect(
                                                    brush = Brush.horizontalGradient(
                                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                                    ),
                                                    blendMode = BlendMode.ColorBurn
                                                )
                                            }
                                    )

                                    // Foreground content
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                .data(album.artUri)
                                                .crossfade(true)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .build(),
                                            contentDescription = album.title,
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            placeholder = ColorPainter(Color(0xFF141414)),
                                            error = ColorPainter(Color(0xFF141414))
                                        )

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = album.title,
                                                style = MaterialTheme.typography.titleLarge,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }


                    if (searchQuery.isBlank() && recentlyPlayedAlbums.isNotEmpty()) {
                        stickyHeader(key = "recently_played_header") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Recently Played",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        item {
                            val itemWidth = (screenWidth - 72.dp) / 3.5f

                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(recentlyPlayedAlbums) { item ->
                                    when (item) {
                                        is com.bitperfect.app.library.RecentlyPlayedItem.AlbumItem -> {
                                            Column(
                                                modifier = Modifier
                                                    .width(itemWidth)
                                                    .clickable { onAlbumClick(item.album) },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsyncImage(
                                                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                        .data(item.album.artUri)
                                                        .crossfade(true)
                                                        .diskCachePolicy(CachePolicy.ENABLED)
                                                        .build(),
                                                    contentDescription = item.album.title,
                                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    placeholder = ColorPainter(Color(0xFF141414)),
                                                    error = ColorPainter(Color(0xFF141414))
                                                )
                                                Text(
                                                    text = item.album.title,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary,
                                                    minLines = 2,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }
                                        }
                                        is com.bitperfect.app.library.RecentlyPlayedItem.ArtistGroupItem -> {
                                            Column(
                                                modifier = Modifier
                                                    .width(itemWidth)
                                                    .clickable { onArtistClick(item.artistName) },
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AsyncImage(
                                                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                        .data(item.thumbnailUrl)
                                                        .crossfade(true)
                                                        .diskCachePolicy(CachePolicy.ENABLED)
                                                        .build(),
                                                    contentDescription = item.artistName,
                                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.CircleShape),
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                    placeholder = ColorPainter(Color(0xFF141414)),
                                                    error = ColorPainter(Color(0xFF141414))
                                                )
                                                Text(
                                                    text = item.artistName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary,
                                                    minLines = 2,
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


                    if (searchQuery.isBlank() && rediscoverAlbums.isNotEmpty()) {
                        stickyHeader(key = "rediscover_header") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "Rediscover",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        item {
                            val itemWidth = (screenWidth - 72.dp) / 3.5f

                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(rediscoverAlbums) { pair ->
                                    val album = pair.second
                                    Column(
                                        modifier = Modifier
                                            .width(itemWidth)
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
                                            color = TextSecondary,
                                            minLines = 2,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    filteredArtists.forEach { artist ->
                        stickyHeader(key = "artist_${artist.id}") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                        item {
                            val defaultWidth = (screenWidth - 72.dp) / 3.5f
                            val itemWidth = if (artist.albums.size < 3) {
                                (defaultWidth * 2) + 16.dp
                            } else {
                                defaultWidth
                            }

                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp)
                            ) {
                                items(artist.albums) { album ->
                                    Column(
                                        modifier = Modifier
                                            .width(itemWidth)
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
                                            color = TextSecondary,
                                            minLines = 2,
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
