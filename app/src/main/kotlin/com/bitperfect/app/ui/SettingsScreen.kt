package com.bitperfect.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitperfect.app.BuildConfig
import com.bitperfect.core.utils.SettingsManager
import com.bitperfect.app.usb.DriveInfo

import com.bitperfect.core.services.DriveOffsetRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    driveOffsetRepository: DriveOffsetRepository,
    viewModel: AppViewModel,
    onNavigateToAbout: () -> Unit = {},
    onCalibrateOffsetClick: () -> Unit = {}
) {
    val driveStatus by viewModel.driveStatus.collectAsState()
    val driveInfo = driveStatus.info

    var outputFolderUri by remember { mutableStateOf(settingsManager.outputFolderUri) }

    // Observe the offsets to trigger recomposition when data loads
    val offsets by driveOffsetRepository.offsets.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            val uriString = it.toString()
            outputFolderUri = uriString
            settingsManager.outputFolderUri = uriString
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(1.dp) // Minimal spacing for the "slabs" feel
    ) {
        item {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    text = "Storage & Paths",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Configure where and how your rips are saved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Destination Folder",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clickable { folderPickerLauncher.launch(null) }
                        ) {
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = "Folder",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                val displayPath = outputFolderUri?.let { uriStr ->
                                    try {
                                        val decoded = java.net.URLDecoder.decode(uriStr, "UTF-8")
                                        decoded.substringAfterLast(":")
                                    } catch (e: Exception) {
                                        uriStr
                                    }
                                } ?: "Not set"
                                Text(
                                    text = "/$displayPath",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Browse")
                        }
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)) {
                Text(
                    text = "DISC DRIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Column {
                val driveStateColor: Color
                val driveStateIcon: androidx.compose.ui.graphics.vector.ImageVector?
                val driveStateIconTint: Color
                val driveStateTextColor: Color
                var isWarningState = false

                if (driveInfo != null) {
                    if (offsets == null) {
                        // Data not loaded yet, use neutral state
                        driveStateColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        driveStateIcon = null
                        driveStateIconTint = MaterialTheme.colorScheme.onSurface
                        driveStateTextColor = MaterialTheme.colorScheme.onSurface
                    } else {
                        val offsetInfo = driveOffsetRepository.findOffset(driveInfo.vendorId, driveInfo.productId)
                        if (offsetInfo != null) {
                            if (offsetInfo.offset != null) {
                                // Match found with offset != null -> Green background
                                driveStateColor = Color(0xFF4CAF50)
                                driveStateIcon = Icons.Default.CheckCircle
                                driveStateIconTint = Color.White
                                driveStateTextColor = Color.White
                            } else {
                                // Match found with offset == null -> Yellow background
                                driveStateColor = Color(0xFFFFC107)
                                driveStateIcon = Icons.Default.Warning
                                driveStateIconTint = Color.Black
                                driveStateTextColor = Color.Black
                                isWarningState = true
                            }
                        } else {
                            // No match found -> Red background
                            driveStateColor = Color(0xFFF44336)
                            driveStateIcon = Icons.Default.Report
                            driveStateIconTint = Color.White
                            driveStateTextColor = Color.White
                        }
                    }
                } else {
                    driveStateColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    driveStateIcon = null
                    driveStateIconTint = MaterialTheme.colorScheme.onSurface
                    driveStateTextColor = MaterialTheme.colorScheme.onSurface
                }

                Surface(
                    color = driveStateColor,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .let {
                            if (isWarningState) {
                                it.clickable { onCalibrateOffsetClick() }
                            } else {
                                it
                            }
                        }
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (driveInfo != null) {
                                    val vendor = driveInfo.vendorId.ifBlank { "" }
                                    val product = driveInfo.productId.ifBlank { "" }
                                    val displayName = if (vendor.isNotBlank() && product.isNotBlank()) {
                                        "$vendor $product"
                                    } else if (vendor.isNotBlank()) {
                                        vendor
                                    } else if (product.isNotBlank()) {
                                        product
                                    } else {
                                        "Unknown Drive"
                                    }
                                    displayName
                                } else "No drive connected",
                                style = MaterialTheme.typography.titleMedium,
                                color = driveStateTextColor
                            )
                            if (driveStateIcon != null) {
                                Icon(
                                    imageVector = driveStateIcon,
                                    contentDescription = null,
                                    tint = driveStateIconTint,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sendDebugInfo(context, driveInfo) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Send Debug Info",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Share drive information for troubleshooting",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Navigate",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAbout() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "About",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Navigate",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private fun sendDebugInfo(context: android.content.Context, driveInfo: DriveInfo?) {
    val sb = java.lang.StringBuilder()
    sb.appendLine("=== MobileRipper Drive Debug Info ===")
    sb.appendLine()

    sb.appendLine("Drive Information:")
    if (driveInfo != null) {
        sb.appendLine("  Vendor: ${driveInfo.vendorId}")
        sb.appendLine("  Model: ${driveInfo.productId}")
    } else {
        sb.appendLine("  Vendor: None")
        sb.appendLine("  Model: None")
    }
    sb.appendLine()

    sb.appendLine("USB Information:")
    if (driveInfo != null) {
        sb.appendLine("  Vendor ID: ${driveInfo.usbVendorId} (0x${driveInfo.usbVendorId.toString(16).uppercase()})")
        sb.appendLine("  Product ID: ${driveInfo.usbProductId} (0x${driveInfo.usbProductId.toString(16).uppercase()})")
        sb.appendLine("  Device Path: ${driveInfo.devicePath}")
    } else {
        sb.appendLine("  Vendor ID: None")
        sb.appendLine("  Product ID: None")
        sb.appendLine("  Device Path: None")
    }
    sb.appendLine()

    sb.appendLine("Drive Capabilities:")
    sb.appendLine("  C2 Error Pointers: No")
    sb.appendLine("  Subchannel Reading: No")
    sb.appendLine("  Full TOC Reading: No")
    sb.appendLine("  Read Offset: 0 samples")
    sb.appendLine()

    sb.appendLine("Transport Settings:")
    sb.appendLine("  Compatibility Mode: Enabled")
    sb.appendLine("  Compatibility Cached: No")
    sb.appendLine()

    sb.appendLine("Phone Information:")
    sb.appendLine("  Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    sb.appendLine("  Device: ${android.os.Build.MODEL}")
    sb.appendLine("  Product: ${android.os.Build.PRODUCT}")

    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, sb.toString())
        type = "text/plain"
    }

    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
}
