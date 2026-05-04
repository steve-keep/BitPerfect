package com.bitperfect.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.library.LibraryRepository
import com.bitperfect.core.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.RipManager
import com.bitperfect.app.usb.RipStatus
import com.bitperfect.app.usb.TrackRipState
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.MusicBrainzRepository
import com.bitperfect.core.services.AccurateRipService

data class TrackListViewState(
    val title: String,
    val artistName: String,
    val coverArtUrl: String?,
    val tracks: List<TrackInfo>,
    val isCdMode: Boolean
)

open class AppViewModel(
    application: Application,
    private val playerRepository: PlayerRepository,
    private val lookupMusicBrainz: suspend (DiscToc) -> DiscMetadata? = { MusicBrainzRepository(application).lookup(it) }
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        PlayerRepository(application)
    )

    private val settingsManager = SettingsManager(application)
    private val libraryRepository = LibraryRepository(application)
    private val accurateRipService = AccurateRipService()

    private val _artists = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artists: StateFlow<List<ArtistInfo>> = _artists

    val searchQuery = MutableStateFlow("")

    private val _isOutputFolderConfigured = MutableStateFlow(false)
    val isOutputFolderConfigured: StateFlow<Boolean> = _isOutputFolderConfigured

    private val _selectedAlbumId = MutableStateFlow<Long?>(null)
    val selectedAlbumId: StateFlow<Long?> = _selectedAlbumId

    private val _selectedAlbumTitle = MutableStateFlow<String?>(null)
    val selectedAlbumTitle: StateFlow<String?> = _selectedAlbumTitle

    open val driveStatus: StateFlow<DriveStatus> = DeviceStateManager.driveStatus

    private val _trackListViewState = MutableStateFlow<TrackListViewState?>(null)
    val trackListViewState: StateFlow<TrackListViewState?> = _trackListViewState

    private val _playingTracks = MutableStateFlow<List<TrackInfo>>(emptyList())

    private val _coverArtUrl = MutableStateFlow<String?>(null)
    open val coverArtUrl: StateFlow<String?> = _coverArtUrl.asStateFlow()

    private val _discMetadata = MutableStateFlow<DiscMetadata?>(null)
    open val discMetadata: StateFlow<DiscMetadata?> = _discMetadata.asStateFlow()

    private val _isKeyDisc = MutableStateFlow(false)
    open val isKeyDisc: StateFlow<Boolean> = _isKeyDisc.asStateFlow()

    private var _ripManager: RipManager? = null

    private val _ripStates = MutableStateFlow<Map<Int, TrackRipState>>(emptyMap())
    val ripStates: StateFlow<Map<Int, TrackRipState>> = _ripStates.asStateFlow()

    val isPlaying: StateFlow<Boolean> = playerRepository.isPlaying
    val currentMediaId: StateFlow<String?> = playerRepository.currentMediaId
    val positionMs: StateFlow<Long> = playerRepository.positionMs

    val upNextQueue: StateFlow<List<androidx.media3.common.MediaItem>> = playerRepository.currentTimeline
    val currentQueueIndex: StateFlow<Int> = playerRepository.currentIndex

    val currentTrackTitle: StateFlow<String?> = playerRepository.currentTrackTitle
    val currentTrackArtist: StateFlow<String?> = playerRepository.currentTrackArtist
    val currentAlbumTitle: StateFlow<String?> = playerRepository.currentAlbumTitle
    val currentAlbumArtUri: StateFlow<android.net.Uri?> = playerRepository.currentAlbumArtUri

    val currentTrack: StateFlow<TrackInfo?> = combine(_playingTracks, currentMediaId) { tracks, mediaId ->
        if (mediaId != null) {
            tracks.find { it.id.toString() == mediaId }
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentAlbum: StateFlow<com.bitperfect.app.library.AlbumInfo?> = combine(_artists, currentTrack) { artistsList, track ->
        if (track != null && track.albumId != -1L) {
            artistsList.flatMap { it.albums }.find { it.id == track.albumId }
        } else {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredArtists: StateFlow<List<ArtistInfo>> = combine(artists, searchQuery) { artistsList, query ->
        if (query.isBlank()) {
            artistsList
        } else {
            val lowerQuery = query.lowercase()
            artistsList.mapNotNull { artist ->
                val artistMatches = artist.name.lowercase().contains(lowerQuery)
                val matchingAlbums = artist.albums.filter { album ->
                    album.title.lowercase().contains(lowerQuery)
                }

                if (artistMatches || matchingAlbums.isNotEmpty()) {
                    artist.copy(albums = if (artistMatches) artist.albums else matchingAlbums)
                } else {
                    null
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadLibrary()
        viewModelScope.launch {
            try {
                playerRepository.connect()
            } catch (e: Exception) {
                // Ignore
            }
        }
        viewModelScope.launch {
            driveStatus.collectLatest { status ->
                if (status is DriveStatus.DiscReady && status.toc != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        _isKeyDisc.value = accurateRipService.checkIsKeyDisc(status.toc)
                    }

                    try {
                        val metadata = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            lookupMusicBrainz(status.toc)
                        }
                        if (metadata != null) {
                            _discMetadata.value = metadata
                        } else {
                            val trackTitles = (1..status.toc.trackCount).map { "Track $it" }
                            _discMetadata.value = DiscMetadata(
                                albumTitle = "Unknown Album",
                                artistName = "Unknown Artist",
                                trackTitles = trackTitles,
                                mbReleaseId = ""
                            )
                        }
                    } catch (e: Exception) {
                        DeviceStateManager.reportError("Network error: ${e.message ?: "Unknown error"}")
                        _discMetadata.value = null
                    }
                } else {
                    _discMetadata.value = null
                    _isKeyDisc.value = false
                }
            }
        }
        viewModelScope.launch {
            discMetadata.collectLatest { metadata ->
                if (metadata != null && metadata.mbReleaseId.isNotEmpty()) {
                    _coverArtUrl.value = "https://coverartarchive.org/release/\${metadata.mbReleaseId}/front"
                } else {
                    _coverArtUrl.value = null
                }
            }
        }
    }

    fun loadLibrary() {
        val uriString = settingsManager.outputFolderUri
        _isOutputFolderConfigured.value = !uriString.isNullOrBlank()

        viewModelScope.launch(Dispatchers.IO) {
            val loadedArtists = libraryRepository.getLibrary(uriString)
            _artists.value = loadedArtists
        }
    }

    fun selectAlbum(albumId: Long, albumTitle: String) {
        _selectedAlbumId.value = albumId
        _selectedAlbumTitle.value = albumTitle
        loadTracks(albumId)
    }

    private fun loadTracks(albumId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val albumTracks = libraryRepository.getTracksForAlbum(albumId)

            var foundAlbum: com.bitperfect.app.library.AlbumInfo? = null
            var foundArtistName = ""
            for (artist in _artists.value) {
                val album = artist.albums.find { it.id == albumId }
                if (album != null) {
                    foundAlbum = album
                    foundArtistName = artist.name
                    break
                }
            }

            val title = foundAlbum?.title ?: _selectedAlbumTitle.value ?: "Unknown Album"
            val coverArtUrl = foundAlbum?.artUri?.toString()

            _trackListViewState.value = TrackListViewState(
                title = title,
                artistName = foundArtistName,
                coverArtUrl = coverArtUrl,
                tracks = albumTracks,
                isCdMode = false
            )
        }
    }

    fun clearTracks() {
        _trackListViewState.value = null
        _ripStates.value = emptyMap()
        _ripManager?.cancel()
        _ripManager = null
    }

    fun viewCdTracks() {
        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value

            val cdTracks = toc.tracks.mapIndexed { index, _ ->
                val trackTitle = meta?.trackTitles?.getOrNull(index) ?: "Track \${index + 1}"
                val nextLba = if (index + 1 < toc.tracks.size) toc.tracks[index + 1].lba else toc.leadOutLba
                val currentLba = toc.tracks[index].lba
                val durationMs = if (nextLba > currentLba) {
                    ((nextLba - currentLba) * 1000L) / 75L
                } else 0L

                TrackInfo(
                    id = index.toLong(),
                    title = trackTitle,
                    trackNumber = index + 1,
                    durationMs = durationMs
                )
            }

            _trackListViewState.value = TrackListViewState(
                title = meta?.albumTitle ?: "Unknown Album",
                artistName = meta?.artistName ?: "Unknown Artist",
                coverArtUrl = coverArtUrl.value,
                tracks = cdTracks,
                isCdMode = true
            )
        }
    }

    fun startRip() {
        val outputUri = settingsManager.outputFolderUri
        if (outputUri.isNullOrBlank()) return

        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value ?: return

            val driveInfo = currentDriveStatus.info

            viewModelScope.launch(Dispatchers.IO) {
                val offset = if (driveInfo != null) {
                    val driveOffsetRepository = com.bitperfect.core.services.DriveOffsetRepository(getApplication<Application>())
                    driveOffsetRepository.findOffset(driveInfo.vendorId, driveInfo.productId)?.offset ?: 0
                } else {
                    0
                }

                val expectedChecksums = accurateRipService.getExpectedChecksums(toc)
                val ripManager = RipManager(
                    context = getApplication<Application>(),
                    outputFolderUriString = outputUri,
                    toc = toc,
                    metadata = meta,
                    expectedChecksums = expectedChecksums,
                    coverArtUrl = _coverArtUrl.value,
                    driveOffset = offset
                )
                _ripManager = ripManager

                launch {
                    ripManager.trackStates.collect { states ->
                        _ripStates.value = states

                        // Check if all tracks are done ripping
                        val isDone = states.values.all {
                            it.status == RipStatus.SUCCESS ||
                            it.status == RipStatus.WARNING ||
                            it.status == RipStatus.ERROR
                        }

                        if (states.isNotEmpty() && isDone) {
                            // Give it a moment to settle, then rescan media and switch to library view
                            withContext(Dispatchers.Main) {
                                loadLibrary()

                                // Find the newly ripped album in the library
                                viewModelScope.launch(Dispatchers.IO) {
                                    kotlinx.coroutines.delay(1000) // Wait for MediaStore
                                    val safeArtist = meta.artistName
                                    val safeAlbum = meta.albumTitle

                                    val newArtists = libraryRepository.getLibrary(outputUri)
                                    val foundArtist = newArtists.find { it.name.equals(safeArtist, ignoreCase = true) }
                                    val foundAlbum = foundArtist?.albums?.find { it.title.equals(safeAlbum, ignoreCase = true) }

                                    if (foundAlbum != null) {
                                        withContext(Dispatchers.Main) {
                                            selectAlbum(foundAlbum.id, foundAlbum.title)
                                        }
                                    } else {
                                        // Fallback, just switch out of CD mode if we can't find it
                                        val currentState = _trackListViewState.value
                                        if (currentState != null) {
                                            _trackListViewState.value = currentState.copy(isCdMode = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                ripManager.startRipping()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerRepository.disconnect()
        _ripManager?.cancel()
    }

    fun playAlbum(tracks: List<TrackInfo>) {
        _playingTracks.value = tracks
        playerRepository.playAlbum(tracks)
    }

    fun playTrack(tracks: List<TrackInfo>, index: Int) {
        _playingTracks.value = tracks
        playerRepository.playTrack(tracks, index)
    }

    fun playNext(track: TrackInfo) {
        playerRepository.playNext(track)
    }

    fun addToQueue(track: TrackInfo) {
        playerRepository.addToQueue(track)
    }

    fun togglePlayPause() {
        playerRepository.togglePlayPause()
    }

    fun seekTo(ms: Long) {
        playerRepository.seekTo(ms)
    }

    fun skipNext() {
        playerRepository.skipNext()
    }

    fun skipPrev() {
        playerRepository.skipPrev()
    }

    fun pollPosition() {
        playerRepository.pollPosition()
    }
}
