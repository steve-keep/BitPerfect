package com.bitperfect.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitperfect.app.library.ArtistInfo
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.app.library.TrackInfo
import com.bitperfect.app.library.LibraryRepository

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
import com.bitperfect.core.models.LyricsFetchResult
import java.net.URL
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
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
    val isCdMode: Boolean,
    val otherAlbums: List<com.bitperfect.app.library.AlbumInfo> = emptyList()
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
    private val fetchItunesArtwork: suspend (String, String, Int?) -> ItunesArtwork? = { artist, album, trackCount ->
        ItunesArtworkRepository(application).fetchItunesArtwork(artist, album, trackCount)
    }
) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)

    private val accurateRipService = AccurateRipService()

    private val _artists = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artists: StateFlow<List<ArtistInfo>> = _artists

    private val _totalTracks = MutableStateFlow(0)
    val totalTracks: StateFlow<Int> = _totalTracks.asStateFlow()


    private val _totalAlbumsCount = MutableStateFlow(0)
    val totalAlbumsCount: StateFlow<Int> = _totalAlbumsCount
    private val _recentlyPlayedAlbums = MutableStateFlow<List<com.bitperfect.app.library.RecentlyPlayedItem>>(emptyList())
    val recentlyPlayedAlbums: StateFlow<List<com.bitperfect.app.library.RecentlyPlayedItem>> = _recentlyPlayedAlbums

    private val _rediscoverAlbums = MutableStateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>>(emptyList())
    val rediscoverAlbums: StateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>> = _rediscoverAlbums

    private val _latestRippedAlbums = MutableStateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>>(emptyList())
    val latestRippedAlbums: StateFlow<List<Pair<com.bitperfect.app.library.ArtistInfo, com.bitperfect.app.library.AlbumInfo>>> = _latestRippedAlbums

    val searchQuery = MutableStateFlow("")

    private val _isOutputFolderConfigured = MutableStateFlow(false)
    val isOutputFolderConfigured: StateFlow<Boolean> = _isOutputFolderConfigured

    private val _selectedAlbumId = MutableStateFlow<Long?>(null)
    val selectedAlbumId: StateFlow<Long?> = _selectedAlbumId

    private val _selectedAlbumTitle = MutableStateFlow<String?>(null)
    val selectedAlbumTitle: StateFlow<String?> = _selectedAlbumTitle

    private val _selectedArtist = MutableStateFlow<com.bitperfect.app.library.ArtistInfo?>(null)
    val selectedArtist: StateFlow<com.bitperfect.app.library.ArtistInfo?> = _selectedArtist


    private val _selectedArtistBio = MutableStateFlow<String?>(null)
    val selectedArtistBio: StateFlow<String?> = _selectedArtistBio
    private val _selectedArtistThumbnail = MutableStateFlow<String?>(null)
    val selectedArtistThumbnail: StateFlow<String?> = _selectedArtistThumbnail

    val activeDevice: StateFlow<OutputDevice> = outputRepository.activeDevice
    val availableDevices: StateFlow<List<OutputDevice>> = outputRepository.availableDevices

    val isDiscovering: StateFlow<Boolean> = outputRepository.isDiscovering
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _showOutputSheet = MutableStateFlow(false)
    val showOutputSheet: StateFlow<Boolean> = _showOutputSheet.asStateFlow()

    val wiimVolume: StateFlow<Int> = outputRepository.wiimVolume

    fun setWiimVolume(volume: Int) {
        viewModelScope.launch {
            outputRepository.setVolume(volume)
        }
    }

    fun adjustWiimVolume(delta: Int) {
        val current = outputRepository.wiimVolume.value
        setWiimVolume((current + delta).coerceIn(0, 100))
    }

    fun openOutputDeviceSheet() {
        outputRepository.refreshDevices()
        _showOutputSheet.value = true
    }

    fun closeOutputDeviceSheet() {
        _showOutputSheet.value = false
    }

    fun selectOutputDevice(device: OutputDevice) {
        val tracks = _playingTracks.value
        val index = currentQueueIndex.value
        outputRepository.switchTo(device, tracks, index)
        outputRepository.userSelectedDevice = device
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

    private data class LyricsFetchKey(
        val mbReleaseId: String
    )

    private val lyricsRepository = LyricsRepository(application)
    private var lastLyricsFetchKey: LyricsFetchKey? = null
    private var lyricsFetchJob: Job? = null
    private val _lyricsMap = MutableStateFlow<Map<Int, LyricsFetchResult>>(emptyMap())
    val lyricsMap: StateFlow<Map<Int, LyricsFetchResult>> = _lyricsMap.asStateFlow()

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

    private val _awaitingEjectToCommit = MutableStateFlow(false)
    val awaitingEjectToCommit: StateFlow<Boolean> = _awaitingEjectToCommit.asStateFlow()
    private var pendingCommitMeta: DiscMetadata? = null

    val isControllerReady: StateFlow<Boolean> = playerRepository.isControllerReady
    val isPlaying: StateFlow<Boolean> = outputRepository.isPlaying
    val currentMediaId: StateFlow<String?> = playerRepository.currentMediaId
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    val lrcLines: StateFlow<List<com.bitperfect.app.player.LrcLine>> = playerRepository.syncedLyrics
        .map { raw -> if (raw != null) com.bitperfect.app.player.parseLrc(raw) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val upNextQueue: StateFlow<List<androidx.media3.common.MediaItem>> = playerRepository.currentTimeline
    val currentQueueIndex: StateFlow<Int> = combine(
        outputRepository.activeDevice,
        outputRepository.wiimCurrentTrackIndex,
        playerRepository.currentIndex
    ) { device, wiimIndex, localIndex ->
        if (device is OutputDevice.Upnp && wiimIndex >= 0) wiimIndex else localIndex
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val currentTrackTitle: StateFlow<String?> = playerRepository.currentTrackTitle
    val currentTrackArtist: StateFlow<String?> = playerRepository.currentTrackArtist
    val currentAlbumTitle: StateFlow<String?> = playerRepository.currentAlbumTitle
    val currentAlbumArtUri: StateFlow<android.net.Uri?> = playerRepository.currentAlbumArtUri

    private val _trackFormatInfo = MutableStateFlow<String?>(null)
    val trackFormatInfo: StateFlow<String?> = _trackFormatInfo.asStateFlow()

    val currentTrack: StateFlow<TrackInfo?> = combine(
        outputRepository.activeDevice,
        _playingTracks,
        outputRepository.wiimCurrentTrackIndex,
        currentMediaId
    ) { device, tracks, wiimIndex, mediaId ->
        if (device is OutputDevice.Upnp && wiimIndex >= 0) {
            tracks.getOrNull(wiimIndex)
        } else {
            if (mediaId != null) tracks.find { it.id.toString() == mediaId } else null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch(ioDispatcher) {
            currentTrack.collectLatest { track ->
                if (track == null || track.dataPath == null) {
                    _trackFormatInfo.value = null
                    return@collectLatest
                }

                try {
                    val file = File(track.dataPath)
                    if (file.exists()) {
                        val audioFile = AudioFileIO.read(file)
                        val header = audioFile.audioHeader

                        val bitRateStr = header.bitRate
                        val bitsPerSample = header.bitsPerSample
                        val sampleRateAsNumber = header.sampleRateAsNumber

                        val sampleRateFormatted = if (sampleRateAsNumber > 0) {
                            val formatted = sampleRateAsNumber / 1000.0
                            if (formatted == formatted.toLong().toDouble()) {
                                "${formatted.toLong()} kHz"
                            } else {
                                "${formatted} kHz"
                            }
                        } else {
                            ""
                        }

                        val bitRateFormatted = if (!bitRateStr.isNullOrEmpty()) "$bitRateStr kbps" else ""
                        val bitDepthFormatted = if (bitsPerSample > 0) "$bitsPerSample-bit" else ""

                        val formatParts = mutableListOf<String>()
                        if (bitRateFormatted.isNotEmpty()) formatParts.add(bitRateFormatted)

                        val depthAndRate = listOf(bitDepthFormatted, sampleRateFormatted).filter { it.isNotEmpty() }.joinToString("/")
                        if (depthAndRate.isNotEmpty()) formatParts.add(depthAndRate)

                        if (formatParts.isNotEmpty()) {
                            _trackFormatInfo.value = formatParts.joinToString(" | ")
                        } else {
                            _trackFormatInfo.value = "Unknown Format"
                        }
                    } else {
                        _trackFormatInfo.value = null
                    }
                } catch (e: Exception) {
                    com.bitperfect.core.utils.AppLogger.e("AppViewModel", "Failed to read audio format: ${e.message}")
                    _trackFormatInfo.value = "Unknown Format"
                }
            }
        }
    }

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
            libraryRepository.onLibraryUpdated.collect {
                loadLibraryLists()
            }
        }

        viewModelScope.launch {
            driveStatus.collect { status ->
                if (_awaitingEjectToCommit.value) {
                    val discGone = status is DriveStatus.Empty ||
                                   status is DriveStatus.NoDrive ||
                                   status is DriveStatus.Connecting
                    if (discGone) {
                        _awaitingEjectToCommit.value = false
                        val meta = pendingCommitMeta
                        val uri = settingsManager.outputFolderUri
                        if (meta != null && uri != null && !hasHandledRipCompletion) {
                            hasHandledRipCompletion = true
                            viewModelScope.launch(ioDispatcher) {
                                commitRippedAlbum(meta, uri)
                                pendingCommitMeta = null
                            }
                        } else {
                            pendingCommitMeta = null
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            playerRepository.onRecentlyPlayedUpdated.collect {
                loadLibraryLists()
            }
        }

        viewModelScope.launch {
            playerRepository.positionMs.collect {
                if (activeDevice.value !is OutputDevice.Upnp) {
                    _positionMs.value = it
                }
            }
        }

        viewModelScope.launch {
            outputRepository.wiimPositionMs.collect {
                if (activeDevice.value is OutputDevice.Upnp) {
                    _positionMs.value = it
                }
            }
        }

        viewModelScope.launch {
            ripSession.ripStates.collect { states ->
                _ripStates.value = states

                // Check if all tracks are done ripping
                val isDone = states.values.all {
                    it.status == RipStatus.SUCCESS ||
                    it.status == RipStatus.UNVERIFIED ||
                    it.status == RipStatus.WARNING ||
                    it.status == RipStatus.ERROR ||
                    it.status == RipStatus.CANCELLED
                }

                val hasErrors = states.values.any {
                    it.status == RipStatus.ERROR ||
                    it.status == RipStatus.CANCELLED
                }

                val hasWarnings = states.values.any {
                    it.status == RipStatus.WARNING
                }

                val hasSuccessTracks = states.values.any {
                    it.status == RipStatus.SUCCESS ||
                    it.status == RipStatus.UNVERIFIED ||
                    it.status == RipStatus.WARNING
                }

                if (states.isNotEmpty() && isDone && !hasHandledRipCompletion) {
                    if (!hasErrors && !hasWarnings) {
                        // Clean path
                        hasHandledRipCompletion = true
                        _awaitingEjectToCommit.value = false
                        pendingCommitMeta = null
                        val capturedMeta = discMetadata.value
                        val capturedOutputUri = settingsManager.outputFolderUri

                        if (capturedMeta != null && capturedOutputUri != null) {
                            viewModelScope.launch(ioDispatcher) {
                                commitRippedAlbum(capturedMeta, capturedOutputUri)
                            }
                        }
                    } else if (hasSuccessTracks) {
                        // Warning/Error path with at least one success: arm eject gate
                        _awaitingEjectToCommit.value = true
                        pendingCommitMeta = discMetadata.value
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
                    val artwork = fetchItunesArtwork(metadata.artistName, metadata.albumTitle, metadata.trackTitles.size.takeIf { it > 0 })
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
            discMetadata.collect { metadata ->
                if (metadata == null) {
                    _lyricsMap.value = emptyMap()
                    lastLyricsFetchKey = null
                    lyricsFetchJob?.cancel()
                    return@collect
                }

                val currentKey = LyricsFetchKey(
                    mbReleaseId = metadata.mbReleaseId
                )

                if (currentKey == lastLyricsFetchKey && currentKey.mbReleaseId.isNotBlank()) {
                    return@collect
                }

                val status = driveStatus.value
                val toc = (status as? DriveStatus.DiscReady)?.toc
                if (toc == null) {
                    _lyricsMap.value = emptyMap()
                    return@collect
                }

                // Launch lyrics fetch disconnected from collect's cancellation scope
                lyricsFetchJob?.cancel()
                lyricsFetchJob = viewModelScope.launch(ioDispatcher) {
                    try {
                        val semaphore = Semaphore(2)
                        val fetchedLyricsMap = coroutineScope {
                            toc.tracks.mapIndexed { i, entry ->
                                async {
                                    val trackTitle = metadata.trackTitles.getOrNull(i) ?: return@async null
                                    val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.effectiveAudioLeadOutLba
                                    val durationSeconds = (nextLba - entry.lba).toLong() * 588.0 / 44100.0
                                    val result = semaphore.withPermit {
                                        lyricsRepository.fetch(
                                            artistName = metadata.artistName,
                                            albumTitle = metadata.albumTitle,
                                            trackTitle = trackTitle,
                                            trackNumber = entry.trackNumber,
                                            mbReleaseId = metadata.mbReleaseId,
                                            durationSeconds = durationSeconds
                                        )
                                    }
                                    Pair(entry.trackNumber, result)
                                }
                            }.awaitAll().filterNotNull().toMap()
                        }
                        _lyricsMap.value = fetchedLyricsMap
                        lastLyricsFetchKey = currentKey
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Expected, do not suppress
                        throw e
                    } catch (e: Exception) {
                        com.bitperfect.core.utils.AppLogger.e("AppViewModel", "Failed to fetch lyrics: ${e.message}")
                    }
                }
            }
        }
    }

    fun shuffleAndPlayArtist() {
        val artist = _selectedArtist.value ?: return
        viewModelScope.launch {
            val allTracks = mutableListOf<TrackInfo>()
            for (album in artist.albums) {
                allTracks.addAll(libraryRepository.getTracksForAlbum(album.id, settingsManager.outputFolderUri))
            }
            if (allTracks.isNotEmpty()) {
                val shuffledTracks = allTracks.shuffled()
                _playingTracks.value = shuffledTracks
                outputRepository.takeOverAndPlay(shuffledTracks, 0)
            }
        }
    }




    fun loadLibraryLists() {
        val uriString = settingsManager.outputFolderUri

        viewModelScope.launch(ioDispatcher) {
            val loadedArtists = libraryRepository.getLibrary(uriString)
            _artists.value = loadedArtists

            _totalTracks.value = libraryRepository.getTotalTracks(uriString)
            _totalAlbumsCount.value = loadedArtists.sumOf { it.albums.size }

            val recent = libraryRepository.getRecentlyPlayedAlbums(uriString, 50)
            val artistCounts = mutableMapOf<String, Int>()
            for ((artistInfo, _) in recent) {
                artistCounts[artistInfo.name] = artistCounts.getOrDefault(artistInfo.name, 0) + 1
            }

            val processedArtists = mutableSetOf<String>()
            val recentlyPlayedItems = mutableListOf<com.bitperfect.app.library.RecentlyPlayedItem>()

            for ((artistInfo, albumInfo) in recent) {
                if (recentlyPlayedItems.size >= 10) break

                val count = artistCounts.getOrDefault(artistInfo.name, 0)
                if (count > 1) {
                    if (processedArtists.contains(artistInfo.name)) {
                        continue
                    }
                    val thumbnailUrl = libraryRepository.getArtistThumbnailUrl(artistInfo.name, uriString)
                    if (thumbnailUrl != null) {
                        recentlyPlayedItems.add(com.bitperfect.app.library.RecentlyPlayedItem.ArtistGroupItem(artistInfo.name, thumbnailUrl))
                        processedArtists.add(artistInfo.name)
                    } else {
                        recentlyPlayedItems.add(com.bitperfect.app.library.RecentlyPlayedItem.AlbumItem(albumInfo))
                    }
                } else {
                    recentlyPlayedItems.add(com.bitperfect.app.library.RecentlyPlayedItem.AlbumItem(albumInfo))
                }
            }
            _recentlyPlayedAlbums.value = recentlyPlayedItems

            // Calculate rediscover albums
            val recentAlbumIds = recent.map { it.second.id }.toSet()
            val allAlbums = loadedArtists.flatMap { artist ->
                artist.albums.map { Pair(artist, it) }
            }
            val rediscoverPool = allAlbums.filter { !recentAlbumIds.contains(it.second.id) }

            if (rediscoverPool.isEmpty()) {
                _rediscoverAlbums.value = emptyList()
            } else {
                _rediscoverAlbums.value = rediscoverPool.shuffled().take(10)
            }

            val latest = libraryRepository.getLatestRippedAlbums(uriString)
            _latestRippedAlbums.value = latest
        }
    }

    fun loadLibrary() {
        val uriString = settingsManager.outputFolderUri
        _isOutputFolderConfigured.value = !uriString.isNullOrBlank()

        viewModelScope.launch(ioDispatcher) {
            val loadedArtists = libraryRepository.getLibrary(uriString)
            _artists.value = loadedArtists

            _totalTracks.value = libraryRepository.getTotalTracks(uriString)

            loadLibraryLists()

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

    fun selectArtist(artistName: String) {
        val artist = _artists.value.find { it.name.equals(artistName, ignoreCase = true) }
        _selectedArtist.value = artist
        _selectedArtistThumbnail.value = null
        _selectedArtistBio.value = null
        if (artist != null) {
            viewModelScope.launch(ioDispatcher) {
                val thumbnailUrl = libraryRepository.getArtistThumbnailUrl(artist.name, settingsManager.outputFolderUri)
                _selectedArtistThumbnail.value = thumbnailUrl
                val bio = libraryRepository.getArtistBio(artist.name, settingsManager.outputFolderUri)
                _selectedArtistBio.value = bio
            }
        }
    }

    private suspend fun reloadTracksInternal(albumId: Long, artists: List<ArtistInfo>) {
        val albumTracks = libraryRepository.getTracksForAlbum(albumId, settingsManager.outputFolderUri)

        var foundAlbum: com.bitperfect.app.library.AlbumInfo? = null
        var foundArtistName = ""
        var otherAlbums = emptyList<com.bitperfect.app.library.AlbumInfo>()
        for (artist in artists) {
            val album = artist.albums.find { it.id == albumId }
            if (album != null) {
                foundAlbum = album
                foundArtistName = artist.name
                otherAlbums = artist.albums.filter { it.id != albumId }
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
            isCdMode = false,
            otherAlbums = otherAlbums
        )
    }

    fun loadTracks(albumId: Long) {
        viewModelScope.launch(ioDispatcher) {
            reloadTracksInternal(albumId, _artists.value)
        }
    }

    fun clearTracks() {
        _trackListViewState.value = null
        if (!ripSession.isRipping.value) {
            ripSession.clearResults()
            hasHandledRipCompletion = false
            _awaitingEjectToCommit.value = false
            pendingCommitMeta = null
        }
    }

    fun ejectDrive() {
        viewModelScope.launch(Dispatchers.IO) {
            DeviceStateManager.ejectDrive()
        }
    }

    fun viewCdTracks() {
        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value

            val cdTracks = toc.tracks.mapIndexed { index, _ ->
                val trackTitle = meta?.trackTitles?.getOrNull(index) ?: "Track \${index + 1}"
                val nextLba = if (index + 1 < toc.tracks.size) toc.tracks[index + 1].lba else toc.effectiveAudioLeadOutLba
                val currentLba = toc.tracks[index].lba
                val durationMs = if (nextLba > currentLba) {
                    ((nextLba - currentLba) * 1000L) / 75L
                } else 0L

                TrackInfo(
                    id = index.toLong(),
                    title = trackTitle,
                    trackNumber = index + 1,
                    durationMs = durationMs,
                    discNumber = meta?.discNumber ?: 1
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
        hasHandledRipCompletion = true
        ripSession.cancel(deleteFiles)
        _awaitingEjectToCommit.value = false
        pendingCommitMeta = null
    }

    fun rescanTrack(trackNumber: Int) {
        val currentDriveStatus = driveStatus.value
        if (currentDriveStatus is DriveStatus.DiscReady && currentDriveStatus.toc != null) {
            val toc = currentDriveStatus.toc
            val meta = discMetadata.value ?: return

            hasHandledRipCompletion = false

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

    private suspend fun commitRippedAlbum(capturedMeta: DiscMetadata, capturedOutputUri: String) {
        // Give it a moment to settle, then rescan media
        withContext(Dispatchers.Main) {
            loadLibrary()
        }

        val safeArtist = capturedMeta.artistName
        val safeAlbum = capturedMeta.albumTitle
        var albumFound = false

        // Poll for up to 10 seconds (10 * 1000ms) for MediaStore to index the files
        for (i in 1..10) {
            kotlinx.coroutines.delay(1000) // Wait for MediaStore

            val newArtists = libraryRepository.getLibrary(capturedOutputUri)
            val foundArtist = newArtists.find { it.name.equals(safeArtist, ignoreCase = true) }
            val foundAlbum = foundArtist?.albums?.find { it.title.equals(safeAlbum, ignoreCase = true) }

            if (foundAlbum != null) {
                val firstTrack = libraryRepository.getTracksForAlbum(foundAlbum.id, capturedOutputUri).firstOrNull()

                libraryRepository.appendNewRelease(
                    outputFolderUriString = capturedOutputUri,
                    albumId = foundAlbum.id,
                    albumTitle = foundAlbum.title,
                    artist = foundArtist?.name ?: safeArtist,
                    trackId = firstTrack?.id
                )
                // reload of lists is handled by flow, just update library structure
                loadLibrary()

                _selectedAlbumId.value = foundAlbum.id
                _selectedAlbumTitle.value = foundAlbum.title
                withContext(Dispatchers.Main) {
                    reloadTracksInternal(foundAlbum.id, newArtists)
                }
                albumFound = true
                break // Exit the polling loop once found
            }
        }

        if (!albumFound && _trackListViewState.value?.isCdMode == true) {
            withContext(Dispatchers.Main) {
                _trackListViewState.value = _trackListViewState.value?.copy(isCdMode = false)
            }
        }
    }

    override fun onCleared() {
        (playerRepository as? PlayerRepository)?.clearSpeakerTypeProvider()
        super.onCleared()
        playerRepository.disconnect()
    }

    fun playAlbum(tracks: List<TrackInfo>) {
        _playingTracks.value = tracks
        viewModelScope.launch {
            outputRepository.takeOverAndPlay(tracks, 0)
        }
    }

    fun playTrack(tracks: List<TrackInfo>, index: Int) {
        _playingTracks.value = tracks
        viewModelScope.launch {
            outputRepository.takeOverAndPlay(tracks, index)
        }
    }

    fun playNext(track: TrackInfo) {
        playerRepository.playNext(track)
        viewModelScope.launch {
            _uiEvent.emit("Added to play next")
        }
    }

    fun addToQueue(track: TrackInfo) {
        val isWiim = outputRepository.activeDevice.value is OutputDevice.Upnp
        if (isWiim) {
            _playingTracks.value = _playingTracks.value + track
            viewModelScope.launch {
                outputRepository.appendToQueue(track)
            }
        } else {
            playerRepository.addToQueue(track)
        }
        viewModelScope.launch {
            _uiEvent.emit("Added to queue")
        }
    }

    fun playAlbumNext(tracks: List<TrackInfo>) {
        playerRepository.playAlbumNext(tracks)
        viewModelScope.launch {
            _uiEvent.emit("Added to play next")
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
        viewModelScope.launch {
            outputRepository.optimisticallyFlipWiimPlaying()
            outputRepository.togglePlayPause()
        }
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch {
            outputRepository.seekTo(ms)
        }
    }

    fun skipNext() {
        viewModelScope.launch {
            if (activeDevice.value is OutputDevice.Upnp) {
                outputRepository.skipNext()
            } else {
                playerRepository.skipNext()
            }
        }
    }

    fun skipPrev() {
        viewModelScope.launch {
            if (activeDevice.value is OutputDevice.Upnp) {
                outputRepository.skipPrev()
            } else {
                playerRepository.skipPrev()
            }
        }
    }

    fun pollPosition() {
        viewModelScope.launch {
            if (activeDevice.value is OutputDevice.Upnp) {
                _positionMs.value = outputRepository.getPositionMs()
            } else {
                playerRepository.pollPosition()
            }
        }
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
                    val speakerTypeProvider = com.bitperfect.app.output.SpeakerTypeProvider(
                        application,
                        CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    )
                    speakerTypeProvider.setOutputRepository(outputRepository)
                    playerRepository.setSpeakerTypeProvider(speakerTypeProvider)

                    AppViewModel(application, playerRepository, outputRepository)
                }
            }
    }
}
