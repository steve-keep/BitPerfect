package com.bitperfect.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.library.LibraryRepository

import com.bitperfect.app.library.AiMix
import com.bitperfect.app.library.AiMixTrack
import com.bitperfect.app.library.AiMixRepository
import com.bitperfect.app.library.AiMixGenerator
import androidx.media3.common.MediaItem
import com.bitperfect.core.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.content.Intent

import com.bitperfect.app.usb.DeviceStateManager
import com.bitperfect.app.usb.DriveStatus
import com.bitperfect.app.usb.RipManager
import com.bitperfect.app.usb.RipStatus
import com.bitperfect.app.usb.TrackRipState
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.services.MusicBrainzRepository
import com.bitperfect.core.services.AccurateRipService
import com.bitperfect.core.services.ItunesArtwork
import com.bitperfect.core.services.ItunesArtworkRepository
import com.bitperfect.core.services.LyricsRepository
import com.bitperfect.core.models.LyricsResult
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import java.io.File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import com.bitperfect.app.output.OutputRepository
import com.bitperfect.app.output.OutputDevice
data class TrackListViewState(
    val title: String,
    val artistName: String,
    val coverArtUrl: String?,
    val tracks: List<TrackInfo>,
    val isCdMode: Boolean
)

data class RipBannerState(
    val isVisible: Boolean,
    val completedTracks: Int,
    val totalTracks: Int,
    val overallProgress: Float,
    val artistName: String,
    val totalTracksLabel: String,
    val artworkBytes: ByteArray?
)

open class AppViewModel(
    application: Application,
    private val playerRepository: PlayerRepository,
    private val outputRepository: OutputRepository,
    private val libraryRepository: LibraryRepository = LibraryRepository(application),
    private val ioDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val lookupMusicBrainz: suspend (DiscToc) -> DiscMetadata? = { MusicBrainzRepository(application).lookup(it) },
    private val fetchItunesArtwork: suspend (String, String) -> ItunesArtwork? = { artist, album ->
        ItunesArtworkRepository(application).fetchItunesArtwork(artist, album)
    },
    private val aiMixRepository: AiMixRepository = AiMixRepository(),
    private val aiMixGenerator: AiMixGenerator = AiMixGenerator(application)
) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)

    private val _aiMixes = MutableStateFlow<List<AiMix>>(emptyList())
    val aiMixes: StateFlow<List<AiMix>> = _aiMixes.asStateFlow()

    private val _aiMixesLoading = MutableStateFlow(false)
    val aiMixesLoading: StateFlow<Boolean> = _aiMixesLoading.asStateFlow()

    private val accurateRipService = AccurateRipService()

    private val _artists = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artists: StateFlow<List<ArtistInfo>> = _artists

    private val _recentlyPlayedAlbums = MutableStateFlow<List<com.bitperfect.app.library.AlbumInfo>>(emptyList())
    val recentlyPlayedAlbums: StateFlow<List<com.bitperfect.app.library.AlbumInfo>> = _recentlyPlayedAlbums

    private val _latestRippedAlbums = MutableStateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>>(emptyList())
    val latestRippedAlbums: StateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>> = _latestRippedAlbums

    val searchQuery = MutableStateFlow("")

    private val _isOutputFolderConfigured = MutableStateFlow(false)
    val isOutputFolderConfigured: StateFlow<Boolean> = _isOutputFolderConfigured

    private val _selectedAlbumId = MutableStateFlow<Long?>(null)
    val selectedAlbumId: StateFlow<Long?> = _selectedAlbumId

    private val _selectedAlbumTitle = MutableStateFlow<String?>(null)
    val selectedAlbumTitle: StateFlow<String?> = _selectedAlbumTitle

    val activeDevice: StateFlow<OutputDevice> = outputRepository.activeDevice
    val availableDevices: StateFlow<List<OutputDevice>> = outputRepository.availableDevices

    private val _showOutputSheet = MutableStateFlow(false)
    val showOutputSheet: StateFlow<Boolean> = _showOutputSheet.asStateFlow()

    fun openOutputDeviceSheet() {
        outputRepository.refreshDevices()
        _showOutputSheet.value = true
    }

    fun closeOutputDeviceSheet() {
        _showOutputSheet.value = false
    }

    fun selectOutputDevice(device: OutputDevice) {
        val mediaIds = upNextQueue.value.map { it.mediaId }
        val index = currentQueueIndex.value
        outputRepository.switchTo(device, mediaIds, index)
        _showOutputSheet.value = false
    }


    open val driveStatus: StateFlow<DriveStatus> = DeviceStateManager.driveStatus

    private val _tagsViewState = MutableStateFlow<List<Pair<String, String>>?>(null)
    val tagsViewState: StateFlow<List<Pair<String, String>>?> = _tagsViewState.asStateFlow()
    internal val _trackListViewState = MutableStateFlow<TrackListViewState?>(null)
    val trackListViewState: StateFlow<TrackListViewState?> = _trackListViewState

    private val _playingTracks = MutableStateFlow<List<TrackInfo>>(emptyList())

    private val _artwork = MutableStateFlow<ItunesArtwork?>(null)
    private val _artworkBytes = MutableStateFlow<ByteArray?>(null)

    private val _lyricsMap = MutableStateFlow<Map<Int, LyricsResult>>(emptyMap())
    val lyricsMap: StateFlow<Map<Int, LyricsResult>> = _lyricsMap.asStateFlow()

    open val coverArtUrl: StateFlow<String?> = _artwork
        .map { it?.previewUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _discMetadata = MutableStateFlow<DiscMetadata?>(null)
    open val discMetadata: StateFlow<DiscMetadata?> = _discMetadata.asStateFlow()

    private val _isKeyDisc = MutableStateFlow(false)
    open val isKeyDisc: StateFlow<Boolean> = _isKeyDisc.asStateFlow()

    private val ripSession = com.bitperfect.app.usb.RipSession.getInstance(application)

    internal val _ripStates = MutableStateFlow<Map<Int, TrackRipState>>(emptyMap())
    open val ripStates: StateFlow<Map<Int, TrackRipState>> = _ripStates.asStateFlow()

    private val _shareIntent = MutableSharedFlow<Intent>(replay = 0, extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    private val _uiEvent = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    val isRipping: StateFlow<Boolean> = ripSession.isRipping

    val ripBannerState: StateFlow<RipBannerState> = combine(
        ripSession.isRipping,
        ripSession.ripStates,
        discMetadata,
        _artworkBytes
    ) { isRipping, states, meta, artworkBytes ->
        val completedStatuses = setOf(
            RipStatus.SUCCESS, RipStatus.UNVERIFIED, RipStatus.WARNING, RipStatus.ERROR, RipStatus.RIPPING
        )
        val completed = states.values.count { it.status in completedStatuses }
        val total = states.size
        val progress = if (total == 0) 0f
            else states.values.map { it.progress }.average().toFloat()
        RipBannerState(
            isVisible = isRipping || states.isNotEmpty(),
            completedTracks = completed,
            totalTracks = total,
            overallProgress = progress,
            artistName = meta?.artistName ?: "",
            totalTracksLabel = "$total tracks",
            artworkBytes = artworkBytes
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RipBannerState(false, 0, 0, 0f, "", "", null)
    )

    private var hasHandledRipCompletion = false

    val isControllerReady: StateFlow<Boolean> = playerRepository.isControllerReady
    val isPlaying: StateFlow<Boolean> = playerRepository.isPlaying
    val currentMediaId: StateFlow<String?> = playerRepository.currentMediaId
    val positionMs: StateFlow<Long> = playerRepository.positionMs

    val lrcLines: StateFlow<List<com.bitperfect.app.player.LrcLine>> = playerRepository.syncedLyrics
        .map { raw -> if (raw != null) com.bitperfect.app.player.parseLrc(raw) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            ripSession.ripStates.collect { states ->
                _ripStates.value = states

                // Check if all tracks are done ripping
                val isDone = states.values.all {
                    it.status == RipStatus.SUCCESS ||
                    it.status == RipStatus.UNVERIFIED ||
                    it.status == RipStatus.WARNING ||
                    it.status == RipStatus.ERROR
                }

                if (states.isNotEmpty() && isDone && !hasHandledRipCompletion) {
                    hasHandledRipCompletion = true
                    // Give it a moment to settle, then rescan media and switch to library view
                    withContext(Dispatchers.Main) {
                        loadLibrary()

                        // Find the newly ripped album in the library
                        viewModelScope.launch(ioDispatcher) {
                            kotlinx.coroutines.delay(1000) // Wait for MediaStore
                            val meta = discMetadata.value ?: return@launch
                            val outputUri = settingsManager.outputFolderUri
                            val safeArtist = meta.artistName
                            val safeAlbum = meta.albumTitle

                            val newArtists = libraryRepository.getLibrary(outputUri)
                            val foundArtist = newArtists.find { it.name.equals(safeArtist, ignoreCase = true) }
                            val foundAlbum = foundArtist?.albums?.find { it.title.equals(safeAlbum, ignoreCase = true) }

                            if (foundAlbum != null) {
                                val firstTrack = libraryRepository.getTracksForAlbum(foundAlbum.id, outputUri).firstOrNull()

                                libraryRepository.appendNewRelease(
                                    outputFolderUriString = outputUri,
                                    albumId = foundAlbum.id,
                                    albumTitle = foundAlbum.title,
                                    artist = foundArtist?.name ?: safeArtist,
                                    trackId = firstTrack?.id
                                )
                                loadLibrary() // Reload after appending to reflect latest rips
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
                    viewModelScope.launch(ioDispatcher) {
                        _isKeyDisc.value = accurateRipService.checkIsKeyDisc(status.toc)
                    }

                    try {
                        val metadata = kotlinx.coroutines.withContext(ioDispatcher) {
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
                    if (ripSession.isRipping.value) {
                        ripSession.cancel()
                    }
                    _discMetadata.value = null
                    _isKeyDisc.value = false
                }
            }
        }
        viewModelScope.launch {
            discMetadata.collectLatest { metadata ->
                if (metadata != null) {
                    val artwork = fetchItunesArtwork(metadata.artistName, metadata.albumTitle)
                    _artwork.value = artwork
                    _artworkBytes.value = null
                    if (artwork != null) {
                        try {
                            _artworkBytes.value = kotlinx.coroutines.withContext(ioDispatcher) {
                                URL(artwork.highResUrl).readBytes()
                            }
                        } catch (e: Exception) {
                            com.bitperfect.core.utils.AppLogger.w("AppViewModel", "High-res art download failed, trying preview: ${e.message}")
                            try {
                                _artworkBytes.value = kotlinx.coroutines.withContext(ioDispatcher) {
                                    URL(artwork.previewUrl).readBytes()
                                }
                            } catch (e2: Exception) {
                                com.bitperfect.core.utils.AppLogger.w("AppViewModel", "Preview art download also failed: ${e2.message}")
                            }
                        }
                    }
                } else {
                    _artwork.value = null
                    _artworkBytes.value = null
                }
            }
        }

        viewModelScope.launch {
            discMetadata.collectLatest { metadata ->
                if (metadata == null) {
                    _lyricsMap.value = emptyMap()
                    return@collectLatest
                }

                if (!settingsManager.embedLyrics) {
                    _lyricsMap.value = emptyMap()
                    return@collectLatest
                }

                val status = driveStatus.value
                val toc = (status as? DriveStatus.DiscReady)?.toc
                if (toc == null) {
                    _lyricsMap.value = emptyMap()
                    return@collectLatest
                }

                try {
                    val fetchedLyricsMap = coroutineScope {
                        toc.tracks.mapIndexed { i, entry ->
                            async {
                                val trackTitle = metadata.trackTitles.getOrNull(i) ?: return@async null
                                val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
                                val durationSeconds = (nextLba - entry.lba).toLong() * 588.0 / 44100.0
                                val result = LyricsRepository(application).fetch(
                                    artistName = metadata.artistName,
                                    albumTitle = metadata.albumTitle,
                                    trackTitle = trackTitle,
                                    trackNumber = entry.trackNumber,
                                    mbReleaseId = metadata.mbReleaseId,
                                    durationSeconds = durationSeconds
                                )
                                if (result != null) {
                                    Pair(entry.trackNumber, result)
                                } else {
                                    null
                                }
                            }
                        }.awaitAll().filterNotNull().toMap()
                    }
                    _lyricsMap.value = fetchedLyricsMap
                } catch (e: Exception) {
                    com.bitperfect.core.utils.AppLogger.e("AppViewModel", "Failed to fetch lyrics concurrently: ${e.message}")
                    // Leave map unchanged or empty? Left unchanged on error as per requirements.
                }
            }
        }
    }



    private fun loadAiMixes() {
        viewModelScope.launch(ioDispatcher) {
            val outputUri = settingsManager.outputFolderUri
            val mixes = aiMixRepository.getLatestMixes(getApplication(), outputUri)
            _aiMixes.value = mixes
            checkAndRegenerateMixes(outputUri)
        }
    }

    private suspend fun checkAndRegenerateMixes(outputUri: String?) {
        try {
            val lastGeneratedAt = aiMixRepository.getLastGeneratedAt(getApplication(), outputUri)
            val now = System.currentTimeMillis()

            if (lastGeneratedAt == null || (now - lastGeneratedAt > 7 * 24 * 60 * 60 * 1000L)) {
                if (!aiMixGenerator.isAvailable()) return

                _aiMixesLoading.value = true

                val summary = aiMixRepository.buildLibrarySummary(getApplication(), outputUri)
                if (summary.isBlank()) {
                    _aiMixesLoading.value = false
                    return
                }

                // Get available tracks to resolve tracks later
                val availableTracks = mutableListOf<AiMixTrack>()
                val artistsList = _artists.value
                for (artist in artistsList) {
                    for (album in artist.albums) {
                        val tracks = libraryRepository.getTracksForAlbum(album.id, outputUri)
                        for (track in tracks) {
                            availableTracks.add(
                                AiMixTrack(
                                    trackId = track.id,
                                    artist = track.artist,
                                    title = track.title,
                                    albumTitle = track.albumTitle,
                                    albumId = album.id
                                )
                            )
                        }
                    }
                }

                val result = aiMixGenerator.generateMixes(summary, availableTracks)
                if (result.isNotEmpty()) {
                    aiMixRepository.appendMixes(getApplication(), outputUri, result)
                    _aiMixes.value = aiMixRepository.getLatestMixes(getApplication(), outputUri)
                }

                _aiMixesLoading.value = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _aiMixesLoading.value = false
        }
    }

    fun playMix(mix: AiMix) {
        val outputUri = settingsManager.outputFolderUri
        viewModelScope.launch(ioDispatcher) {
            val resolvedTracks = mix.tracks.mapNotNull {
                libraryRepository.getTrack(it.trackId, outputUri)
            }
            if (resolvedTracks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    playAlbum(resolvedTracks)
                }
            }
        }
    }

    fun loadLibrary() {
        val uriString = settingsManager.outputFolderUri
        _isOutputFolderConfigured.value = !uriString.isNullOrBlank()

        viewModelScope.launch(ioDispatcher) {
            val loadedArtists = libraryRepository.getLibrary(uriString)
            _artists.value = loadedArtists

            val recent = libraryRepository.getRecentlyPlayedAlbums(uriString)
            _recentlyPlayedAlbums.value = recent.map { it.second }

            val latest = libraryRepository.getLatestRippedAlbums(uriString)
            _latestRippedAlbums.value = latest

            loadAiMixes()

            // Rehydrate the track list if an album was selected but data was lost
            // (e.g. after returning from background or process recreation).
            val albumId = _selectedAlbumId.value
            if (albumId != null && _trackListViewState.value == null) {
                reloadTracksInternal(albumId, loadedArtists)
            }
        }
    }

    fun selectAlbum(albumId: Long, albumTitle: String) {
        clearTracks()
        _selectedAlbumId.value = albumId
        _selectedAlbumTitle.value = albumTitle
        loadTracks(albumId)
    }

    private suspend fun reloadTracksInternal(albumId: Long, artists: List<ArtistInfo>) {
        val albumTracks = libraryRepository.getTracksForAlbum(albumId, settingsManager.outputFolderUri)

        var foundAlbum: com.bitperfect.app.library.AlbumInfo? = null
        var foundArtistName = ""
        for (artist in artists) {
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

    private fun loadTracks(albumId: Long) {
        viewModelScope.launch(ioDispatcher) {
            reloadTracksInternal(albumId, _artists.value)
        }
    }

    fun clearTracks() {
        _trackListViewState.value = null
        if (!ripSession.isRipping.value) {
            ripSession.clearResults()
        }
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

    fun cancelRip(deleteFiles: Boolean) {
        ripSession.cancel(deleteFiles)
    }

    fun rescanTrack(trackNumber: Int) {
        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value ?: return

            viewModelScope.launch(ioDispatcher) {
                val expectedChecksums = accurateRipService.getExpectedChecksums(toc)
                val lyrics = _lyricsMap.value[trackNumber]
                val trackLyricsMap = if (lyrics != null) mapOf(trackNumber to lyrics) else emptyMap()

                ripSession.startRip(
                    outputFolderUriString = settingsManager.outputFolderUri ?: "",
                    toc = toc,
                    metadata = meta,
                    expectedChecksums = expectedChecksums,
                    artworkBytes = _artworkBytes.value,
                    lyricsMap = trackLyricsMap,
                    tracksToRip = listOf(trackNumber)
                )
            }
        }
    }

    fun startRip() {
        val outputUri = settingsManager.outputFolderUri
        if (outputUri.isNullOrBlank()) return

        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value ?: return

            hasHandledRipCompletion = false

            viewModelScope.launch(ioDispatcher) {
                val expectedChecksums = accurateRipService.getExpectedChecksums(toc)
                ripSession.startRip(
                    outputFolderUriString = outputUri,
                    toc = toc,
                    metadata = meta,
                    expectedChecksums = expectedChecksums,
                    artworkBytes = _artworkBytes.value,
                    lyricsMap = _lyricsMap.value
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerRepository.disconnect()
    }

    fun playAlbum(tracks: List<TrackInfo>) {
        _playingTracks.value = tracks
        playerRepository.playAlbum(tracks)
        outputRepository.play()
    }

    fun playTrack(tracks: List<TrackInfo>, index: Int) {
        _playingTracks.value = tracks
        playerRepository.playTrack(tracks, index)
        outputRepository.play()
    }

    fun playNext(track: TrackInfo) {
        playerRepository.playNext(track)
        viewModelScope.launch {
            _uiEvent.emit("Added to play next")
        }
    }

    fun addToQueue(track: TrackInfo) {
        playerRepository.addToQueue(track)
        viewModelScope.launch {
            _uiEvent.emit("Added to queue")
        }
    }

    fun addAlbumToQueue(tracks: List<TrackInfo>) {
        playerRepository.addAlbumToQueue(tracks)
    }

    fun clearQueue() {
        playerRepository.clearQueue()
    }

    fun removeQueueItem(index: Int) {
        playerRepository.removeMediaItem(index)
    }

    fun moveQueueItem(currentIndex: Int, newIndex: Int) {
        playerRepository.moveMediaItem(currentIndex, newIndex)
    }

    fun togglePlayPause() {
        outputRepository.togglePlayPause(isPlaying.value)
    }

    fun seekTo(ms: Long) {
        outputRepository.seekTo(ms)
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

    fun shareRipInfo(trackNumber: Int) {
        val state = _ripStates.value[trackNumber] ?: return
        if (state.status != RipStatus.WARNING && state.status != RipStatus.ERROR) return

        val meta = discMetadata.value
        val trackTitle = meta?.trackTitles?.getOrNull(trackNumber - 1) ?: "Track $trackNumber"
        val albumTitle = meta?.albumTitle ?: "Unknown Album"
        val artistName = meta?.artistName ?: "Unknown Artist"

        val isError = state.status == RipStatus.ERROR
        val subjectTitle = if (isError) "Rip Error" else "AccurateRip mismatch"

        val body = buildString {
            if (isError) {
                appendLine("Track Rip Error")
            } else {
                appendLine("AccurateRip verification failed")
            }
            appendLine()
            appendLine("Track:    $trackTitle")
            appendLine("Album:    $albumTitle")
            appendLine("Artist:   $artistName")
            appendLine("Track #:  $trackNumber")
            appendLine()

            if (isError) {
                appendLine("Error details:")
                appendLine(state.errorMessage ?: "Unknown error")
            } else {
                val expectedHex = state.expectedChecksums
                    .joinToString(", ") { "0x${it.toString(16).uppercase().padStart(8, '0')}" }
                val computedHex = state.computedChecksum
                    ?.let { "0x${it.toString(16).uppercase().padStart(8, '0')}" }
                    ?: "unknown"
                appendLine("Computed checksum:  $computedHex")
                appendLine("Expected checksums: $expectedHex")
                appendLine()
                appendLine("AccurateRip URL:")
                appendLine(state.accurateRipUrl ?: "unavailable")
            }
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BitPerfect: $subjectTitle – $trackTitle")
            putExtra(Intent.EXTRA_TEXT, body)
        }

        viewModelScope.launch {
            _shareIntent.emit(intent)
        }
    }

    fun loadTagsForTrack(track: TrackInfo) {
        viewModelScope.launch(ioDispatcher) {
            val path = track.dataPath ?: return@launch
            val file = File(path)
            if (!file.exists()) return@launch
            try {
                val f = AudioFileIO.read(file)
                val tag = f.tag
                val tagList = mutableListOf<Pair<String, String>>()
                if (tag is FlacTag && tag.vorbisCommentTag != null) {
                    val vorbis = tag.vorbisCommentTag
                    val it = vorbis.fields
                    while (it.hasNext()) {
                        val field = it.next()
                        tagList.add(field.id to field.toString())
                    }
                } else if (tag is VorbisCommentTag) {
                    val it = tag.fields
                    while (it.hasNext()) {
                        val field = it.next()
                        tagList.add(field.id to field.toString())
                    }
                }
                _tagsViewState.value = tagList
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearTags() {

        _tagsViewState.value = null
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val playerRepository = PlayerRepository(application)
                    val outputRepository = OutputRepository(
                        application,
                        playerRepository,
                        CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    )
                    AppViewModel(application, playerRepository, outputRepository)
                }
            }
    }
}
