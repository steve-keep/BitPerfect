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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.RipStatus

private fun numberToWord(n: Int): String {
    val words = arrayOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")
    return if (n in 0..10) words[n] else n.toString()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    viewModel: AppViewModel,
    onShareRipInfo: (trackNumber: Int) -> Unit,
    onNavigateBack: () -> Unit
) {
    val viewState by viewModel.trackListViewState.collectAsState()
    val isCdMode = viewState?.isCdMode == true

    DisposableEffect(isCdMode) {
        onDispose {
            if (!isCdMode) {
                viewModel.clearTracks()
            }
        }
    }

    val currentMediaId by viewModel.currentMediaId.collectAsState()
    val ripStates by viewModel.ripStates.collectAsState()

    val isRipping = remember(ripStates) {
        ripStates.isNotEmpty() && !ripStates.values.all {
            it.status == RipStatus.SUCCESS ||
            it.status == RipStatus.UNVERIFIED ||
            it.status == RipStatus.WARNING ||
            it.status == RipStatus.ERROR
        }
    }

    val driveStatus by viewModel.driveStatus.collectAsState()

    LaunchedEffect(driveStatus) {
        if (driveStatus is DriveStatus.NoDrive && !isRipping && isCdMode) {
            onNavigateBack()
        }
    }

    var showStopDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        val state = viewState
        if (state == null || state.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            val groupedTracks = remember(state.tracks) { state.tracks.groupBy { it.discNumber } }
            val isMultiDisc = groupedTracks.size > 1

            val trackIndices = remember(state.tracks) {
                state.tracks.mapIndexed { index, track -> track.id to index }.toMap()
            }

            LazyColumn(
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

                    AlbumHeader(
                        title = state.title,
                        artistName = state.artistName,
                        coverArtUrl = state.coverArtUrl,
                        trackCount = state.tracks.size,
                        isCdMode = state.isCdMode,
                        isRipping = isRipping,
                        overallProgress = overallProgress,
                        isFullyVerified = isFullyVerified,
                        onSaveClick = { viewModel.startRip() },
                        onPlayClick = { viewModel.playAlbum(state.tracks) },
                        onAddToQueueClick = { viewModel.addAlbumToQueue(state.tracks) },
                        onStopRipClick = {
                            showStopDialog = true
                        }
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
                        val globalIndex = trackIndices[track.id] ?: 0
                        val isCurrentTrack = track.id.toString() == currentMediaId
                        val tintColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        val titleColor = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        val ripState = ripStates[track.trackNumber]
                        var showRipDetailSheet by remember { mutableStateOf(false) }

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
                                                    color = MaterialTheme.colorScheme.primary,
                                                    trackColor = Color.DarkGray,
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        }
                                        RipStatus.SUCCESS -> {
                                            IconButton(
                                                onClick = { showRipDetailSheet = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Success - tap for details",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        RipStatus.WARNING -> {
                                            IconButton(
                                                onClick = { showRipDetailSheet = true },
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
                                                onClick = { showRipDetailSheet = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.RemoveCircle,
                                                    contentDescription = "Unverified",
                                                    tint = Color.Gray,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        RipStatus.ERROR -> {
                                            IconButton(
                                                onClick = { showRipDetailSheet = true },
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
                            if (showRipDetailSheet && ripState != null) {
                                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                ModalBottomSheet(
                                    onDismissRequest = { showRipDetailSheet = false },
                                    sheetState = sheetState
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .padding(bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val statusColor = when (ripState.status) {
                                            RipStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                                            RipStatus.WARNING -> Color(0xFFFFC107)
                                            RipStatus.UNVERIFIED -> Color.Gray
                                            RipStatus.ERROR -> Color(0xFFF44336)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }

                                        // Header
                                        Text(
                                            text = "${track.trackNumber.toString().padStart(2, '0')} - ${track.title}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Surface(
                                            color = statusColor.copy(alpha = 0.1f),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = ripState.status.name,
                                                color = statusColor,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                                        // Diagnostic section
                                        val durationExpected = ripState.totalSectors * 588 / 44100.0
                                        Text("LBA Range     ${ripState.startLba} → ${ripState.endLba}", style = MaterialTheme.typography.bodyMedium)

                                        val isTruncated = ripState.sectorsRead != ripState.totalSectors
                                        Text(
                                            buildAnnotatedString {
                                                append("Sectors       ${ripState.totalSectors} expected  /  ${ripState.sectorsRead} read")
                                                if (isTruncated) {
                                                    withStyle(SpanStyle(color = Color(0xFFF44336))) {
                                                        append("   *** TRUNCATED ***")
                                                    }
                                                }
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text("Duration      ${String.format(java.util.Locale.US, "%.2f", ripState.durationSeconds)}s ripped  /  ${String.format(java.util.Locale.US, "%.2f", durationExpected)}s expected", style = MaterialTheme.typography.bodyMedium)

                                        // Checksum section
                                        if (ripState.computedChecksum != null) {
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                            val computedStr = String.format("0x%08X", ripState.computedChecksum and 0xFFFFFFFFL)
                                            when (ripState.status) {
                                                RipStatus.SUCCESS -> {
                                                    Text("Checksum  $computedStr  ✓ matched", style = MaterialTheme.typography.bodyMedium)
                                                }
                                                RipStatus.WARNING -> {
                                                    Text("Computed   $computedStr", style = MaterialTheme.typography.bodyMedium)
                                                    val expectedStr = ripState.expectedChecksums.joinToString(", ") { String.format("0x%08X", it and 0xFFFFFFFFL) }
                                                    Text("Expected   $expectedStr", style = MaterialTheme.typography.bodyMedium)
                                                }
                                                RipStatus.UNVERIFIED -> {
                                                    Text("Checksum  $computedStr  (not in AccurateRip database)", style = MaterialTheme.typography.bodyMedium)
                                                }
                                                else -> {} // ERROR may or may not have a checksum, but usually doesn't output it specifically
                                            }
                                        }

                                        if (ripState.status == RipStatus.ERROR && ripState.errorMessage != null) {
                                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                            Text("Error  ${ripState.errorMessage}", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF44336))
                                        }

                                        // Action row
                                        if (ripState.status == RipStatus.WARNING || ripState.status == RipStatus.ERROR) {
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                OutlinedButton(
                                                    onClick = {
                                                        viewModel.rescanTrack(track.trackNumber)
                                                        showRipDetailSheet = false
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
                                                        onShareRipInfo(track.trackNumber)
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
                                    if (track.isAccurateRipVerified) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "AccurateRip Verified",
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
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

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = {
                showStopDialog = false
            },
            title = { Text("Delete Rip Files?") },
            text = { Text("Do you want to delete the files created during this rip?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelRip(true)
                    showStopDialog = false
                }) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelRip(false)
                    showStopDialog = false
                }) {
                    Text("No")
                }
            }
        )
    }
}
