package com.bitperfect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.app.output.OutputDevice
import com.bitperfect.app.ui.theme.VerificationGreen

@Composable
fun OutputDeviceSheet(
    devices: List<OutputDevice>,
    activeDevice: OutputDevice,
    isDiscovering: Boolean = false,
    onDeviceSelected: (OutputDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 16.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Connect",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (isDiscovering) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        devices.forEach { device ->
            val isActive = device == activeDevice

            val rowContent = @Composable {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(device) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (device) {
                        is OutputDevice.ThisPhone -> Icons.Default.PhoneAndroid
                        is OutputDevice.Bluetooth -> Icons.Default.BluetoothAudio
                        is OutputDevice.Upnp -> Icons.Default.Speaker
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) VerificationGreen else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = device.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) VerificationGreen else Color.White
                        )

                        if (device is OutputDevice.Bluetooth) {
                            val subtitle = buildString {
                                append("Bluetooth")
                                if (device.batteryPercent != null) {
                                    append(" · ")
                                    append(when {
                                        device.batteryPercent > 60 -> "High"
                                        device.batteryPercent > 30 -> "Medium"
                                        else -> "Low"
                                    })
                                }
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        } else if (device is OutputDevice.Upnp) {
                            val subtitle = buildString {
                                if (device.manufacturer != null) append("${device.manufacturer} ")
                                if (device.modelName != null) append("${device.modelName} ")
                                if (device.manufacturer != null || device.modelName != null) append("· ")
                                append("Wi-Fi · FLAC lossless")
                            }

                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            if (isActive) {
                Surface(
                    color = Color.White.copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    rowContent()
                }
            } else {
                rowContent()
            }
        }

        if (isDiscovering && devices.none { it is OutputDevice.Upnp }) {
            Text(
                text = "Scanning for speakers on Wi-Fi…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            )
        }
    }
}
