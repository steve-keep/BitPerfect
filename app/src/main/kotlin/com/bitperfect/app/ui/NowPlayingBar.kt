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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

@Composable
fun NowPlayingBar(
    isPlaying: Boolean,
    currentTrackTitle: String?,
    currentTrackArtist: String?,
    currentAlbumArtUri: Uri?,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dominantColor by remember { mutableStateOf(Color.Transparent) }

    LaunchedEffect(currentAlbumArtUri) {
        if (currentAlbumArtUri == null) {
            dominantColor = Color.Transparent
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF191C20),
        tonalElevation = 3.dp
    ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick() }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                dominantColor.copy(alpha = 0.4f),
                                Color.Transparent
                            ),
                            center = Offset(0f, 0f),
                            radius = 800f
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 8.dp,
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 8.dp
                        )
                        .padding(
                            bottom = WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding()
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
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                if (state is AsyncImagePainter.State.Success) {
                                    val bitmap = state.result.drawable.toBitmap()
                                    Palette.from(bitmap).generate { palette ->
                                        palette?.dominantSwatch?.rgb?.let { colorInt ->
                                            dominantColor = Color(colorInt)
                                        } ?: run {
                                            dominantColor = Color.Transparent
                                        }
                                    }
                                }
                            }
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

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.testTag("now_playing_play_pause")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                }
            }
    }
}
