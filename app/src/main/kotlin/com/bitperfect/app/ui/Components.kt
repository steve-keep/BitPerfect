package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bitperfect.app.usb.DriveStatus

@Composable
fun DeviceList(driveStatus: DriveStatus) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (driveStatus) {
            is DriveStatus.NoDrive -> DriveStatusCard(
                icon = Icons.Default.UsbOff,
                headline = "No Drive Connected",
                subtitle = "Connect a USB CD drive via OTG"
            )
            is DriveStatus.Connecting -> DriveStatusCard(
                icon = Icons.Default.HourglassEmpty,
                headline = "Connecting…",
                subtitle = "Detecting drive capabilities",
                showSpinner = true
            )
            is DriveStatus.PermissionDenied -> DriveStatusCard(
                icon = Icons.Default.Lock,
                headline = "Access Denied",
                subtitle = "Re-connect and allow access when prompted"
            )
            is DriveStatus.NotOptical -> DriveStatusCard(
                icon = Icons.Default.Usb,
                headline = "Unsupported Device",
                subtitle = "Connected device is not a CD drive"
            )
            is DriveStatus.Empty -> DriveStatusCard(
                icon = Icons.Default.Album,
                headline = "No Disc Inserted",
                subtitle = "Insert a CD to continue"
            )
            is DriveStatus.DiscReady -> DriveStatusCard(
                icon = Icons.Default.CheckCircle,
                headline = "Disc Ready",
                subtitle = "${driveStatus.info.vendorId} · ${driveStatus.info.productId}"
            )
            is DriveStatus.Error -> DriveStatusCard(
                icon = Icons.Default.Error,
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall, // Typically Manrope per AGENTS.md rules if theme applies it
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium, // Typically Inter per AGENTS.md rules
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
