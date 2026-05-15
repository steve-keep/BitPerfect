package com.bitperfect.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bitperfect.app.ui.theme.VerificationGreen

@Composable
fun NowPlayingBar(
    isPlaying: Boolean,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentAlbumArtUri: Uri?,
    enabled: Boolean,
    isExternalOutput: Boolean,
    onPlayPause: () -> Unit,
    onOutputDeviceClick: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding()
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onExpand)
                            .padding(
                                top = 8.dp,
                                start = 16.dp,
                                bottom = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentAlbumArtUri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(currentAlbumArtUri)
                                    .allowHardware(false)
                                    .build(),
                                contentDescription = "Album Art",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF141414))
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("now_playing_text_column")
                        ) {
                            Text(
                                text = currentTrackTitle ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("now_playing_title")
                            )
                            if (!currentTrackArtist.isNullOrEmpty()) {
                                Text(
                                    text = currentTrackArtist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("now_playing_artist")
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (enabled) {
                        val outputInteractionSource = remember { MutableInteractionSource() }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .testTag("now_playing_output_device")
                                .semantics { role = Role.Button }
                                .clickable(
                                    interactionSource = outputInteractionSource,
                                    indication = LocalIndication.current,
                                    onClick = onOutputDeviceClick
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth, // fallback to Bluetooth
                                contentDescription = "Output Device",
                                tint = if (isExternalOutput) VerificationGreen else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .testTag("now_playing_play_pause")
                            .semantics { role = Role.Button }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = LocalIndication.current,
                                enabled = enabled,
                                onClick = onPlayPause
                            )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
    }
}
