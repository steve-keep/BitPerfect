package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.app.usb.DriveStatus

@Composable
fun DeviceList(driveStatus: DriveStatus) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
