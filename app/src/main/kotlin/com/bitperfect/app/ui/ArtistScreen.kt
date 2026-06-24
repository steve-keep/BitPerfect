package com.bitperfect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bitperfect.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    viewModel: AppViewModel,
    onNavigateToAlbum: (Long, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val artist by viewModel.selectedArtist.collectAsState()
    val thumbnailUrl by viewModel.selectedArtistThumbnail.collectAsState()
    val bio by viewModel.selectedArtistBio.collectAsState()
    val totalAlbumsCount by viewModel.totalAlbumsCount.collectAsState()
    val similarArtists by viewModel.similarArtists.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (artist != null) {
            val listState = rememberLazyListState()
            val density = LocalDensity.current

            // Reusing typical hero image fade distances
            val fadeEndPx = remember(density) { with(density) { 260.dp.toPx() } }

            val topBarAlpha by remember {
                derivedStateOf {
                    if (listState.firstVisibleItemIndex > 0) 1f
                    else {
                        val offset = listState.firstVisibleItemScrollOffset.toFloat()
                        (offset / fadeEndPx).coerceIn(0f, 1f)
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                    ) {
                        if (!thumbnailUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = thumbnailUrl,
                                contentDescription = "Artist Thumbnail",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }

                        // Gradient Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Transparent,
                                            Color(0xD9000000) // ~85% Black
                                        )
                                    )
                                )
                        )

                        // Artist Title text on top of image
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = artist!!.name,
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (artist!!.albums.isNotEmpty()) {
                                    Text(
                                        text = pluralStringResource(id = R.plurals.albums_count, count = artist!!.albums.size, artist!!.albums.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.shuffleAndPlayArtist() },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(56.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Shuffle and Play Artist",
                                    tint = Color.Black,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }


                if (!bio.isNullOrEmpty()) {
                    item {
                        var isExpanded by remember { mutableStateOf(false) }
                        var hasOverflow by remember { mutableStateOf(false) }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "About",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = bio!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { textLayoutResult ->
                                        if (!isExpanded && textLayoutResult.hasVisualOverflow) {
                                            hasOverflow = true
                                        }
                                    }
                                )
                                if (hasOverflow || isExpanded) {
                                    Text(
                                        text = if (isExpanded) "Read less" else "Read more",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clickable { isExpanded = !isExpanded }
                                    )
                                }
                            }
                        }
                    }
                }
                if (similarArtists.isNotEmpty()) {

                    item {

                        Column(modifier = Modifier.padding(vertical = 8.dp)) {

                            Text(

                                text = "Similar Artists",

                                style = MaterialTheme.typography.titleMedium,

                                fontWeight = FontWeight.Bold,

                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)

                            )

                            LazyRow(

                                horizontalArrangement = Arrangement.spacedBy(12.dp),

                                contentPadding = PaddingValues(horizontal = 16.dp)

                            ) {

                                items(similarArtists) { similar ->

                                    val thumbUrl by produceState<String?>(initialValue = null, similar.name) {

                                        value = viewModel.getSimilarArtistThumbnailUrl(similar.name)

                                    }

                                    Column(

                                        modifier = Modifier

                                            .width(88.dp)

                                            .clickable { viewModel.selectArtist(similar.name) },

                                        horizontalAlignment = Alignment.CenterHorizontally

                                    ) {

                                        AsyncImage(

                                            model = thumbUrl,

                                            contentDescription = similar.name,

                                            modifier = Modifier

                                                .size(72.dp)

                                                .clip(androidx.compose.foundation.shape.CircleShape)

                                                .background(MaterialTheme.colorScheme.surfaceVariant),

                                            contentScale = ContentScale.Crop

                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(

                                            text = similar.name,

                                            style = MaterialTheme.typography.labelSmall,

                                            textAlign = TextAlign.Center,

                                            maxLines = 2,

                                            overflow = TextOverflow.Ellipsis

                                        )

                                    }

                                }

                            }

                        }

                    }

                }

                items(artist!!.albums) { album ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToAlbum(album.id, album.title) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = album.artUri,
                            contentDescription = "Album Art",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = album.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Collapsing Top App Bar overlay
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha)
                    )
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = artist?.name ?: "Unknown Artist",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = topBarAlpha)
                        )
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(40.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Artist not found", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
