package com.bitperfect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bitperfect.core.engine.BitPerfectDrive
import com.bitperfect.core.engine.DriveCapabilities
import com.bitperfect.core.engine.RipState
import com.bitperfect.core.engine.AlbumMetadata

@Composable
fun PreferenceItem(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null
) {
    // Implementing "Tonal Layering": Items sit on surfaceContainerLow
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    trailing()
                }
            }
        }
    }
}

@Composable
fun PreferenceSwitch(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    PreferenceItem(
        title = title,
        description = description,
        icon = icon,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    )
}

@Composable
fun PreferencesHintCard(
    title: String,
    description: String,
    icon: ImageVector = Icons.Default.Info
) {
    // "Editorial Tonal Scale": primary for titles, on-surface-variant for data
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = MaterialTheme.shapes.extraLarge // 1.5rem / 24dp
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BitPerfectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

@Composable
fun DeviceList(
    devices: List<BitPerfectDrive>,
    onDeviceClick: (BitPerfectDrive) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(
            text = "Select Drive",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.primary
        )

        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connect a USB CD drive",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp) // "The List Rule": spacing instead of dividers
        ) {
            items(devices) { drive ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { onDeviceClick(drive) }
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = drive.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${drive.manufacturer ?: "Unknown Manufacturer"} (${if (drive is BitPerfectDrive.Virtual) "Virtual" else "USB"})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "ID: ${drive.identifier}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun CapabilityBadge(label: String, supported: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (supported) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (supported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticDashboard(
    driveCapabilities: DriveCapabilities?,
    ripState: RipState,
    logs: List<String>,
    onStartRip: () -> Unit,
    onEject: () -> Unit,
    onLoadTray: () -> Unit,
    onCopyDebugReport: () -> Unit,
    onRetry: () -> Unit,
    onMetadataSelect: (AlbumMetadata?) -> Unit = {}
) {
    var showMetadataSheet by remember { mutableStateOf(false) }

    // If there's available metadata but none selected, show the sheet automatically
    LaunchedEffect(ripState.availableMetadata, ripState.selectedMetadata) {
        if (ripState.availableMetadata.isNotEmpty() && ripState.selectedMetadata == null) {
            if (ripState.availableMetadata.size == 1) {
                // Auto-select if only 1
                onMetadataSelect(ripState.availableMetadata.first())
            } else {
                showMetadataSheet = true
            }
        }
    }

    if (showMetadataSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMetadataSheet = false
            }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Select Release",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn {
                    items(ripState.availableMetadata) { metadata ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onMetadataSelect(metadata)
                                    showMetadataSheet = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (metadata.albumArtUrl != null) {
                                AsyncImage(
                                    model = metadata.albumArtUrl,
                                    contentDescription = "Album Art",
                                    modifier = Modifier
                                        .size(64.dp)
                                        .padding(end = 16.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .padding(end = 16.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                }
                            }
                            Column {
                                Text(text = metadata.album, style = MaterialTheme.typography.titleMedium)
                                Text(text = metadata.artist, style = MaterialTheme.typography.bodyMedium)
                                val extraInfo = listOfNotNull(metadata.year, metadata.country, metadata.label).joinToString(" • ")
                                if (extraInfo.isNotBlank()) {
                                    Text(text = extraInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (metadata.source == "GnuDB") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Text(
                                            text = "Source: GnuDB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    item {
                        TextButton(
                            onClick = {
                                onMetadataSelect(AlbumMetadata())
                                showMetadataSheet = false
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("Proceed with unnamed tracks")
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Tonal Layering: Cards on background
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Hardware Information",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val info = if (driveCapabilities != null) "${driveCapabilities.vendor} ${driveCapabilities.product} (Rev: ${driveCapabilities.revision})" else "Run diagnostics to detect drive"
                Text(text = info, style = MaterialTheme.typography.titleMedium)

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Detected Capabilities",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (driveCapabilities != null) {
                    CapabilityBadge("Accurate Stream", driveCapabilities.accurateStream)
                    CapabilityBadge("C2 Error Pointers", driveCapabilities.supportsC2)
                    CapabilityBadge("Cache detected", driveCapabilities.hasCache)
                    val offsetText = if (driveCapabilities.offsetFromAccurateRip) {
                        "Read Offset: ${if (driveCapabilities.readOffset > 0) "+" else ""}${driveCapabilities.readOffset} samples (from AccurateRip database)"
                    } else {
                        "Read Offset: ${driveCapabilities.readOffset}"
                    }
                    Text(text = offsetText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(text = "No capabilities detected yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Drive Status",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ripState.driveStatus,
                        style = MaterialTheme.typography.displaySmall, // Large for status
                        color = when (ripState.driveStatus) {
                            "Ready" -> MaterialTheme.colorScheme.primary
                            "No Disc / Tray Open" -> MaterialTheme.colorScheme.tertiary // Warn instead of Alarm
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (ripState.isTrayOperationInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onEject,
                        enabled = !ripState.isRunning && !ripState.isTrayOperationInProgress,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Eject")
                    }
                    OutlinedButton(
                        onClick = onLoadTray,
                        enabled = !ripState.isRunning && !ripState.isTrayOperationInProgress,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Load Tray")
                    }
                }
            }
        }


        // Track List / Error State
        if (ripState.tocError != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Close, contentDescription = "Error", tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(text = ripState.tocError ?: "Error", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        } else if (ripState.discToc != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Take up space instead of terminal
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Track List",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val selectedMeta = ripState.selectedMetadata ?: AlbumMetadata()

                    if (selectedMeta.album != "Unknown Album") {
                        Text(
                            text = "${selectedMeta.album} - ${selectedMeta.artist}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(ripState.discToc?.tracks ?: emptyList()) { track ->
                            val trackTitle = selectedMeta.tracks.getOrNull(track.number - 1)

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text(text = "${track.number}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                                    Text(
                                        text = trackTitle ?: if (track.isAudio) "Audio" else "Data",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = 16.dp)
                                    )
                                }
                                val seconds = track.durationSectors / 75
                                val m = seconds / 60
                                val s = seconds % 60
                                Text(text = String.format("%d:%02d", m, s), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        item {
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Total Duration", style = MaterialTheme.typography.titleSmall)
                                val totalSeconds = ripState.discToc?.totalDurationSectors?.div(75) ?: 0
                                val tm = totalSeconds / 60
                                val ts = totalSeconds % 60
                                Text(text = String.format("%d:%02d", tm, ts), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }

        if (ripState.isRunning || ripState.progress > 0) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Ripping Status: ${ripState.status}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LinearProgressIndicator(
                        progress = { ripState.progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "Track ${ripState.currentTrack}/${ripState.totalTracks}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${(ripState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Primary Action: Gradient fill
            Button(
                onClick = onStartRip,
                enabled = ripState.driveStatus == "Ready" && !ripState.isTrayOperationInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    if (ripState.driveStatus == "Ready") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    if (ripState.driveStatus == "Ready") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Start Secure Rip", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Terminal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onCopyDebugReport) {
                Text("Copy Debug Report")
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.large
        ) {
            LazyColumn(
                modifier = Modifier.padding(16.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = "> $log",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = if (log.contains("Failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
