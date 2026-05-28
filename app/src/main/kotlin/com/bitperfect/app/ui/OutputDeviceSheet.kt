package com.bitperfect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.bitperfect.app.output.OutputDevice
import com.bitperfect.app.ui.theme.VerificationGreen
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputDeviceSheet(
    devices: List<OutputDevice>,
    activeDevice: OutputDevice,
    isDiscovering: Boolean = false,
    wiimVolume: Int = 50,
    onWiimVolumeChanged: (Int) -> Unit = {},
    onDeviceSelected: (OutputDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableFloatStateOf(wiimVolume / 100f) }
    var isDraggingVolume by remember { mutableStateOf(false) }

    LaunchedEffect(wiimVolume) {
        if (!isDraggingVolume) {
            sliderPosition = wiimVolume / 100f
        }
    }

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

        android.util.Log.d("OutputDeviceSheet", "Rendering output devices: ${devices.map { it.displayName }}")

        devices.forEach { device ->
            androidx.compose.runtime.key(device.id) {
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
                    Column {
                        rowContent()

                        val hasExternalVolume = device is OutputDevice.Upnp
                        if (hasExternalVolume) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 24.dp, end = 24.dp, bottom = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeDown,
                                    contentDescription = null,
                                    tint = VerificationGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Slider(
                                    value = sliderPosition,
                                    onValueChange = {
                                        isDraggingVolume = true
                                        sliderPosition = it
                                    },
                                    onValueChangeFinished = {
                                        isDraggingVolume = false
                                        onWiimVolumeChanged((sliderPosition * 100).roundToInt())
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                        .semantics {
                                            contentDescription = "WiiM volume, ${(sliderPosition * 100).roundToInt()}%"
                                        },
                                    colors = androidx.compose.material3.SliderDefaults.colors(
                                        thumbColor = VerificationGreen,
                                        activeTrackColor = VerificationGreen,
                                        inactiveTrackColor = VerificationGreen.copy(alpha = 0.3f)
                                    ),
                                    thumb = {
                                        androidx.compose.material3.SliderDefaults.Thumb(
                                            interactionSource = remember { MutableInteractionSource() },
                                            modifier = Modifier.size(12.dp),
                                            colors = androidx.compose.material3.SliderDefaults.colors(
                                                thumbColor = VerificationGreen
                                            )
                                        )
                                    },
                                    track = { sliderState ->
                                        androidx.compose.material3.SliderDefaults.Track(
                                            sliderState = sliderState,
                                            modifier = Modifier.height(2.dp),
                                            colors = androidx.compose.material3.SliderDefaults.colors(
                                                activeTrackColor = VerificationGreen,
                                                inactiveTrackColor = VerificationGreen.copy(alpha = 0.3f)
                                            )
                                        )
                                    }
                                )
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = VerificationGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                rowContent()
            }
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
