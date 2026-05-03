package com.bitperfect.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.MusicBrainzRepository
import com.bitperfect.core.services.AccurateRipService

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

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks

    private val _selectedAlbumId = MutableStateFlow<Long?>(null)
    val selectedAlbumId: StateFlow<Long?> = _selectedAlbumId

    private val _selectedAlbumTitle = MutableStateFlow<String?>(null)
    val selectedAlbumTitle: StateFlow<String?> = _selectedAlbumTitle

    open val driveStatus: StateFlow<DriveStatus> = DeviceStateManager.driveStatus

    private val _playingTracks = MutableStateFlow<List<TrackInfo>>(emptyList())


    private val _coverArtUrl = MutableStateFlow<String?>(null)
    open val coverArtUrl: StateFlow<String?> = _coverArtUrl.asStateFlow()

    private val _discMetadata = MutableStateFlow<DiscMetadata?>(null)
    open val discMetadata: StateFlow<DiscMetadata?> = _discMetadata.asStateFlow()

    private val _isKeyDisc = MutableStateFlow(false)
    open val isKeyDisc: StateFlow<Boolean> = _isKeyDisc.asStateFlow()

    val isPlaying: StateFlow<Boolean> = playerRepository.isPlaying
    val currentMediaId: StateFlow<String?> = playerRepository.currentMediaId
    val positionMs: StateFlow<Long> = playerRepository.positionMs

    val currentTrackTitle: StateFlow<String?> = playerRepository.currentTrackTitle
    val currentTrackArtist: StateFlow<String?> = playerRepository.currentTrackArtist
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
                    // If artist name matches, show all their albums, otherwise show only matching albums
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
                // Ignore in tests
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
                    _coverArtUrl.value = "https://coverartarchive.org/release/${metadata.mbReleaseId}/front"
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
            _tracks.value = libraryRepository.getTracksForAlbum(albumId)
        }
    }

    fun clearTracks() {
        _tracks.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        playerRepository.disconnect()
    }

    fun playAlbum(tracks: List<TrackInfo>) {
        _playingTracks.value = tracks
        playerRepository.playAlbum(tracks)
    }

    fun playTrack(tracks: List<TrackInfo>, index: Int) {
        _playingTracks.value = tracks
        playerRepository.playTrack(tracks, index)
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
