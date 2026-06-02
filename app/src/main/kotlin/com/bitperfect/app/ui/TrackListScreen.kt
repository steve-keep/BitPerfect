package com.bitperfect.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.RipStatus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.launch
import com.bitperfect.app.ui.theme.TextSecondary

private fun numberToWord(n: Int): String {
    val words = arrayOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    return if (n in 0..10) words[n] else n.toString()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    viewModel: AppViewModel,
    onShareRipInfo: (trackNumber: Int) -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val viewState by viewModel.trackListViewState.collectAsState()
    val isCdMode = viewState?.isCdMode == true


    val currentMediaId by viewModel.currentMediaId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val ripStates by viewModel.ripStates.collectAsState()
    val tagsViewState by viewModel.tagsViewState.collectAsState()

    val isRipping = remember(ripStates) {
        ripStates.isNotEmpty() && !ripStates.values.all {
            it.status == RipStatus.SUCCESS ||
            it.status == RipStatus.UNVERIFIED ||
            it.status == RipStatus.WARNING ||
            it.status == RipStatus.ERROR
        }
    }

    val driveStatus by viewModel.driveStatus.collectAsState()
    val awaitingEjectToCommit by viewModel.awaitingEjectToCommit.collectAsState()

    LaunchedEffect(driveStatus, awaitingEjectToCommit) {
        val isDiscGone = driveStatus is DriveStatus.NoDrive || driveStatus is DriveStatus.Empty || driveStatus is DriveStatus.Open || driveStatus is DriveStatus.NotOptical || driveStatus is DriveStatus.Connecting
        if (isDiscGone && !isRipping && !awaitingEjectToCommit && isCdMode) {
            onNavigateBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val state = viewState
        if (state == null || state.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary // No dominant color available yet
                )
            }

            TopAppBar(
                title = { Text(text = "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        } else {
            val groupedTracks = remember(state.tracks) { state.tracks.groupBy { it.discNumber } }
            val isMultiDisc = groupedTracks.size > 1

            val trackIndices = remember(state.tracks) {
                state.tracks.mapIndexed { index, track -> track.id to index }.toMap()
            }

            var dominantColor by remember { mutableStateOf(Color(0xFF141414)) }
            val fallbackColor = MaterialTheme.colorScheme.primary
            val displayColor = if (dominantColor == Color(0xFF141414)) fallbackColor else dominantColor
            val listState = rememberLazyListState()

        var selectedRipDetail by remember {
            mutableStateOf<Pair<com.bitperfect.app.library.TrackInfo, com.bitperfect.app.usb.TrackRipState>?>(null)
        }

            val density = androidx.compose.ui.platform.LocalDensity.current
            val fadeStartPx = remember(density) { with(density) { 240.dp.toPx() } }
            val fadeEndPx = remember(density) { with(density) { 340.dp.toPx() } }

            val topBarAlpha by remember {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 1f
                    else {
                        val offset = listState.firstVisibleItemScrollOffset.toFloat()
                        (offset / fadeEndPx).coerceIn(0f, 1f)
                    }
                }
            }

            val albumHeaderAlpha by remember {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 0f
                    else {
                        val offset = listState.firstVisibleItemScrollOffset.toFloat()
                        if (offset < fadeStartPx) 1f
                        else (1f - ((offset - fadeStartPx) / (fadeEndPx - fadeStartPx))).coerceIn(0f, 1f)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                item {
                    val overallProgress = remember(ripStates) {
                        if (ripStates.isEmpty()) 0f
                        else {
                            val states = ripStates.values
                            val completed = states.count {
                                it.status == RipStatus.SUCCESS ||
                                it.status == RipStatus.UNVERIFIED ||
                                it.status == RipStatus.WARNING
                            }
                            if (completed == states.size) 1f
                            else states.map { it.progress }.average().toFloat()
                        }
                    }

                    val isFullyVerified = remember(state.tracks) {
                        state.tracks.isNotEmpty() && state.tracks.all { it.isAccurateRipVerified }
                    }

                    val isAlbumPlaying = remember(isPlaying, currentMediaId, state.tracks) {
                        isPlaying && state.tracks.any { it.id.toString() == currentMediaId }
                    }

                    Box(modifier = Modifier.graphicsLayer { this.alpha = albumHeaderAlpha }) {
                        AlbumHeader(
                            title = state.title,
                            artistName = state.artistName,
                            coverArtUrl = state.coverArtUrl,
                            trackCount = state.tracks.size,
                            isCdMode = state.isCdMode,
                            isRipping = isRipping && state.isCdMode,
                            overallProgress = overallProgress,
                            isFullyVerified = isFullyVerified,
                            isAlbumPlaying = isAlbumPlaying,
                            isAwaitingEject = awaitingEjectToCommit,
                            onEjectClick = { viewModel.ejectDrive() },
                            onArtistClick = { onNavigateToArtist(state.artistName) },
                            onSaveClick = { viewModel.startRip() },
                            onPlayClick = {
                                if (isAlbumPlaying) viewModel.togglePlayPause() else viewModel.playAlbum(state.tracks)
                            },
                            onStopRipClick = {
                                viewModel.cancelRip(true)
                                viewModel.ejectDrive()
                            },
                            dominantColor = dominantColor,
                            onColorExtracted = { extractedColor -> dominantColor = extractedColor },
                            primaryColor = displayColor
                        )
                    }
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

                    itemsIndexed(discTracks, key = { index, track -> "track_${track.id}_$index" }) { _, track ->
                        val globalIndex = trackIndices[track.id] ?: 0
                        val isCurrentTrack = track.id.toString() == currentMediaId
                        val tintColor = if (isCurrentTrack) displayColor else MaterialTheme.colorScheme.onSurfaceVariant
                        val titleColor = if (isCurrentTrack) displayColor else MaterialTheme.colorScheme.onSurface
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
                                                    modifier = Modifier.size(24.dp),
                                                    color = displayColor,
                                                    trackColor = Color.DarkGray,
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        }
                                        RipStatus.SUCCESS -> {
                                            IconButton(
                                                onClick = { if (ripState != null) selectedRipDetail = Pair(track, ripState) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Success - tap for details",
                                                    tint = displayColor,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        RipStatus.WARNING -> {
                                            IconButton(
                                                onClick = { if (ripState != null) selectedRipDetail = Pair(track, ripState) },
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
                                        RipStatus.UNVERIFIED -> {
                                            IconButton(
                                                onClick = { if (ripState != null) selectedRipDetail = Pair(track, ripState) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.RemoveCircle,
                                                    contentDescription = "Unverified",
                                                    tint = Color.White.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        RipStatus.ERROR -> {
                                            IconButton(
                                                onClick = { if (ripState != null) selectedRipDetail = Pair(track, ripState) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Error,
                                                    contentDescription = "Rip error – tap to share details",
                                                    tint = Color(0xFFF44336),
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        else -> {}
                                    }
                                } else {
                                    Text(
                                        text = track.trackNumber.toString().padStart(2, '0'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tintColor,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = titleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                val durationSeconds = track.durationMs / 1000
                                val minutes = durationSeconds / 60
                                val seconds = durationSeconds % 60
                                Text(
                                    text = "$minutes:${seconds.toString().padStart(2, '0')}",
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
                                            tint = MaterialTheme.colorScheme.onSurface
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
                                        DropdownMenuItem(
                                            text = { Text("View Tags") },
                                            onClick = {
                                                viewModel.loadTagsForTrack(track)
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

                if (state.otherAlbums.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp)
                        ) {
                            Text(
                                text = "Other albums by ${state.artistName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            )
                            val configuration = LocalConfiguration.current
                            val screenWidth = configuration.screenWidthDp.dp
                            val albumWidth = (screenWidth - 48.dp) / 2.5f

                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(state.otherAlbums) { album ->
                                    val coroutineScope = rememberCoroutineScope()
                                    Column(
                                        modifier = Modifier
                                            .width(albumWidth)
                                            .clickable {
                                                coroutineScope.launch {
                                                    listState.scrollToItem(0)
                                                }
                                                viewModel.selectAlbum(album.id, album.title)
                                            }
                                    ) {
                                        AsyncImage(
                                            model = album.artUri,
                                            contentDescription = "Album Art",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(albumWidth)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.DarkGray)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = album.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = state.artistName,
                                            style = MaterialTheme.typography.bodySmall,
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
            } // Close LazyColumn here

            Box(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = topBarAlpha),
                                MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha)
                            )
                        )
                    )
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = topBarAlpha)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (!state.isCdMode) {
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Play Next") },
                                        onClick = {
                                            viewModel.playAlbumNext(state.tracks)
                                            showMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to Queue") },
                                        onClick = {
                                            viewModel.addAlbumToQueue(state.tracks)
                                            showMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

                    val detail = selectedRipDetail
                    if (detail != null) {
                        val (detailTrack, detailRipState) = detail
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            onDismissRequest = { selectedRipDetail = null },
                            sheetState = sheetState
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val statusColor = when (detailRipState.status) {
                                    RipStatus.SUCCESS -> displayColor
                                    RipStatus.WARNING -> Color(0xFFFFC107)
                                    RipStatus.UNVERIFIED -> Color.White.copy(alpha = 0.5f)
                                    RipStatus.ERROR -> Color(0xFFF44336)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }

                                // Header
                                Text(
                                    text = "${detailTrack.trackNumber.toString().padStart(2, '0')} - ${detailTrack.title}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Surface(
                                    color = statusColor.copy(alpha = 0.1f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = detailRipState.status.name,
                                        color = statusColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                // Diagnostic section
                                val durationExpected = detailRipState.totalSectors * 588 / 44100.0
                                Text("LBA Range     ${detailRipState.startLba} → ${detailRipState.endLba}", style = MaterialTheme.typography.bodyMedium)

                                val isTruncated = detailRipState.sectorsRead != detailRipState.totalSectors
                                Text(
                                    buildAnnotatedString {
                                        append("Sectors       ${detailRipState.totalSectors} expected  /  ${detailRipState.sectorsRead} read")
                                        if (isTruncated) {
                                            withStyle(SpanStyle(color = Color(0xFFF44336))) {
                                                append("   *** TRUNCATED ***")
                                            }
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text("Duration      ${String.format(java.util.Locale.US, "%.2f", detailRipState.durationSeconds)}s ripped  /  ${String.format(java.util.Locale.US, "%.2f", durationExpected)}s expected", style = MaterialTheme.typography.bodyMedium)

                                // Checksum section
                                if (detailRipState.computedChecksumV1 != null || detailRipState.computedChecksumV2 != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                    val computedV2 = detailRipState.computedChecksumV2?.let { String.format("0x%08X", it and 0xFFFFFFFFL) } ?: "unknown"
                                    val computedV1 = detailRipState.computedChecksumV1?.let { String.format("0x%08X", it and 0xFFFFFFFFL) } ?: "unknown"
                                    val expectedV2 = detailRipState.expectedChecksumsV2.joinToString(", ") { String.format("0x%08X", it and 0xFFFFFFFFL) }
                                    val expectedV1 = detailRipState.expectedChecksumsV1.joinToString(", ") { String.format("0x%08X", it and 0xFFFFFFFFL) }

                                    when (detailRipState.status) {
                                        RipStatus.SUCCESS -> {
                                            val v2MatchedStr = if (detailRipState.matchedVersion == 2) "  ✓ matched" else ""
                                            val v1MatchedStr = if (detailRipState.matchedVersion == 1) "  ✓ matched" else ""

                                            Text("Computed (v2)   $computedV2$v2MatchedStr", style = MaterialTheme.typography.bodyMedium)
                                            if (expectedV2.isNotEmpty()) {
                                                Text("Expected (v2)   $expectedV2", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Text("Computed (v1)   $computedV1$v1MatchedStr", style = MaterialTheme.typography.bodyMedium)
                                            if (expectedV1.isNotEmpty()) {
                                                Text("Expected (v1)   $expectedV1", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        RipStatus.WARNING -> {
                                            Text("Computed (v2)   $computedV2", style = MaterialTheme.typography.bodyMedium)
                                            if (expectedV2.isNotEmpty()) {
                                                Text("Expected (v2)   $expectedV2", style = MaterialTheme.typography.bodyMedium)
                                            } else {
                                                Text("Expected (v2)   none", style = MaterialTheme.typography.bodyMedium)
                                            }
                                            Text("Computed (v1)   $computedV1", style = MaterialTheme.typography.bodyMedium)
                                            if (expectedV1.isNotEmpty()) {
                                                Text("Expected (v1)   $expectedV1", style = MaterialTheme.typography.bodyMedium)
                                            } else {
                                                Text("Expected (v1)   none", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        RipStatus.UNVERIFIED -> {
                                            Text("Checksum (v2)  $computedV2  (not in AccurateRip database)", style = MaterialTheme.typography.bodyMedium)
                                            Text("Checksum (v1)  $computedV1  (not in AccurateRip database)", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        else -> {
                                            Text("Checksum (v2)  $computedV2", style = MaterialTheme.typography.bodyMedium)
                                            Text("Checksum (v1)  $computedV1", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }

                                if (detailRipState.status == RipStatus.ERROR && detailRipState.errorMessage != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    Text("Error  ${detailRipState.errorMessage}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF44336))
                                }

                                // Action row
                                if (detailRipState.status == RipStatus.WARNING || detailRipState.status == RipStatus.ERROR) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.rescanTrack(detailTrack.trackNumber)
                                                selectedRipDetail = null
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Rescan",
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Rescan")
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                onShareRipInfo(detailTrack.trackNumber)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share",
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Share Details")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                        }
                    }
        } // Close Box here
    } // Close if statement here
    } // Close Box modifier fillMaxSize here

    if (tagsViewState != null) {
        val tagsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearTags() },
            sheetState = tagsSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Track Tags",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = tagsViewState?.filePath ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                val tagsList = tagsViewState?.tags ?: emptyList()
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(tagsList.size) { index ->
                        val (key, value) = tagsList[index]
                        Column(modifier = Modifier.padding(bottom = 12.dp)) {
                            Text(text = key, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }
}
