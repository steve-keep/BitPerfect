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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus

class AppViewModel(
    application: Application,
    private val playerRepository: PlayerRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, PlayerRepository(application))

    private val settingsManager = SettingsManager(application)
    private val libraryRepository = LibraryRepository(application)

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

    val driveStatus: StateFlow<DriveStatus> = DeviceStateManager.driveStatus

    private val _playingTracks = MutableStateFlow<List<TrackInfo>>(emptyList())

    val isPlaying: StateFlow<Boolean> = playerRepository.isPlaying
    val currentMediaId: StateFlow<String?> = playerRepository.currentMediaId
    val positionMs: StateFlow<Long> = playerRepository.positionMs

    val currentTrackTitle: StateFlow<String?> = combine(_playingTracks, currentMediaId) { playingTracks, mediaId ->
        playingTracks.find { it.id.toString() == mediaId }?.title
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
}
