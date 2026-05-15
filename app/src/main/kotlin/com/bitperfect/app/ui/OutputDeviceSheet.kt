package com.bitperfect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
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
    onDeviceSelected: (OutputDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Connect",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 16.dp)
        )

        devices.forEach { device ->
            val isActive = device.id == activeDevice.id

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
                        is OutputDevice.Bluetooth -> Icons.Default.Bluetooth // Using fallback as default here
                        else -> Icons.Default.PhoneAndroid // fallback
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isActive) VerificationGreen else Color.White.copy(alpha = 0.7f)
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
                                    append(
                                        when {
                                            device.batteryPercent > 60 -> "High"
                                            device.batteryPercent > 30 -> "Medium"
                                            else -> "Low"
                                        }
                                    )
                                }
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
    }
}
