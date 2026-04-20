package com.bitperfect.core.engine

import com.bitperfect.core.utils.ChecksumUtils
import com.bitperfect.core.utils.MusicBrainzUtils
import com.bitperfect.driver.IScsiDriver
import com.bitperfect.driver.ScsiDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class RipState(
    val isRunning: Boolean = false,
    val currentTrack: Int = 0,
    val totalTracks: Int = 0,
    val currentSector: Long = 0,
    val isTrayOperationInProgress: Boolean = false,
    val totalSectors: Long = 0,
    val progress: Float = 0f,
    val status: String = "Idle",
    val reReads: Int = 0,
    val errorCount: Int = 0,
    val driveStatus: String = "No Drive",
    val discToc: DiscToc? = null,
    val tocError: String? = null,
    val availableMetadata: List<AlbumMetadata> = emptyList(),
    val selectedMetadata: AlbumMetadata? = null
)

data class DriveCapabilities(
    val vendor: String = "",
    val product: String = "",
    val revision: String = "",
    val accurateStream: Boolean = false,
    val readOffset: Int = 0,
    val hasCache: Boolean = false,
    val cacheSizeKb: Int = 0,
    val supportsC2: Boolean = false,
    val offsetFromAccurateRip: Boolean = false
)

class RippingEngine(
    private val context: Context,
    private val defaultScsiDriver: IScsiDriver,
    private val flacEncoder: FlacEncoder = FlacEncoder(),
    private val metadataService: MetadataService = MetadataService(),
    private val accurateRipService: AccurateRipService = AccurateRipService()
) {
    var driveOffsetService: DriveOffsetService = DriveOffsetService(context)
    private val _ripState = MutableStateFlow(RipState())
    val ripState: StateFlow<RipState> = _ripState.asStateFlow()

    fun cancel() {
        _ripState.value = _ripState.value.copy(isRunning = false, status = "Cancelled")
    }

    fun selectMetadata(metadata: AlbumMetadata?) {
        _ripState.value = _ripState.value.copy(selectedMetadata = metadata)
    }

    suspend fun startBurstRip(
        context: Context,
        fd: Int,
        outputPath: String,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Reading TOC...")

        val toc = TocReader(scsiDriver).readToc(fd, endpointIn, endpointOut)
        if (toc == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        _ripState.value = _ripState.value.copy(totalTracks = toc.trackCount, status = "Found ${toc.trackCount} tracks")

        // Simplified: Just rip the first track for now
        val firstTrack = toc.tracks.firstOrNull()
        if (firstTrack == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "No tracks found")
            return@withContext
        }

        val startLba = firstTrack.startLba.toLong()
        val totalSectors = firstTrack.durationSectors.toLong().coerceAtMost(50L) // Small range for testing
        val endLba = startLba + totalSectors

        _ripState.value = _ripState.value.copy(totalSectors = totalSectors, currentTrack = 1)

        val outputStream = getOutputStreamForPath(context, outputPath) ?: return@withContext
        flacEncoder.prepare(outputStream, 44100, 2)

        for (lba in startLba until endLba) {
            val sectorData = readSector(fd, lba, scsiDriver, endpointIn, endpointOut)
            if (sectorData == null) {
                _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read sector $lba")
                return@withContext
            }

            flacEncoder.encode(sectorData)

            val currentSector = lba - startLba + 1
            _ripState.value = _ripState.value.copy(
                currentSector = currentSector,
                progress = currentSector.toFloat() / totalSectors,
                status = "Ripping sector $currentSector/$totalSectors"
            )
        }

        flacEncoder.finish()
        _ripState.value = _ripState.value.copy(isRunning = false, status = "Rip Complete", progress = 1f)
    }

    suspend fun startSecureRip(
        context: Context,
        fd: Int,
        outputPath: String,
        capabilities: DriveCapabilities,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Initializing Secure Rip...")

        val toc = TocReader(scsiDriver).readToc(fd, endpointIn, endpointOut)
        if (toc == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        _ripState.value = _ripState.value.copy(totalTracks = toc.trackCount, status = "Found ${toc.trackCount} tracks")

        val firstTrack = toc.tracks.firstOrNull()
        if (firstTrack == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "No tracks found")
            return@withContext
        }

        val startLba = firstTrack.startLba.toLong()
        val totalSectors = firstTrack.durationSectors.toLong().coerceAtMost(50L) // Small range for testing
        val endLba = startLba + totalSectors
        _ripState.value = _ripState.value.copy(totalSectors = totalSectors, currentTrack = 1)

        val outputStream = getOutputStreamForPath(context, outputPath) ?: return@withContext
        flacEncoder.prepare(outputStream, 44100, 2)

        for (lba in startLba until endLba) {
            val sectorData = readSectorSecure(fd, lba, capabilities, scsiDriver, endpointIn, endpointOut)
            if (sectorData == null) {
                _ripState.value = _ripState.value.copy(isRunning = false, status = "Fatal error at sector $lba")
                return@withContext
            }

            flacEncoder.encode(sectorData)

            val currentSector = lba - startLba + 1
            _ripState.value = _ripState.value.copy(
                currentSector = currentSector,
                progress = currentSector.toFloat() / totalSectors,
                status = "Secure Ripping sector $currentSector/$totalSectors"
            )
        }

        flacEncoder.finish()
        _ripState.value = _ripState.value.copy(isRunning = false, status = "Secure Rip Complete", progress = 1f)
    }


    private fun readSector(
        fd: Int,
        lba: Long,
        scsiDriver: IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        includeC2: Boolean = false
    ): ByteArray? {
        val expectedLength = if (includeC2) 2352 + 294 else 2352
        val byte9 = if (includeC2) 0x12.toByte() else 0x10.toByte()

        val readCdCmd = byteArrayOf(
            0xBE.toByte(), 0,
            ((lba shr 24) and 0xFF).toByte(),
            ((lba shr 16) and 0xFF).toByte(),
            ((lba shr 8) and 0xFF).toByte(),
            (lba and 0xFF).toByte(),
            0, 0, 1, // 1 sector
            byte9,
            0
        )

        return scsiDriver.executeScsiCommand(fd, readCdCmd, expectedLength, endpointIn, endpointOut)
    }

    private fun readSectorSecure(
        fd: Int,
        lba: Long,
        capabilities: DriveCapabilities,
        scsiDriver: IScsiDriver,
        endpointIn: Int,
        endpointOut: Int
    ): ByteArray? {
        // Pass 1
        var data1 = readSector(fd, lba, scsiDriver, endpointIn, endpointOut, capabilities.supportsC2) ?: return null

        // If C2 is supported, we can potentially trust it if no errors reported
        if (capabilities.supportsC2 && !hasC2Errors(data1)) {
            return stripC2(data1)
        }

        if (capabilities.hasCache) {
            flushCache(fd, lba, capabilities, scsiDriver, endpointIn, endpointOut)
        }

        // Pass 2
        var data2 = readSector(fd, lba, scsiDriver, endpointIn, endpointOut, capabilities.supportsC2) ?: return null

        if (compareSectors(data1, data2)) {
            return if (capabilities.supportsC2) stripC2(data1) else data1
        }

        // Mismatch! Start re-reads
        Log.w("RippingEngine", "Mismatch at LBA $lba, starting re-reads")
        val attempts = mutableListOf<ByteArray>()
        attempts.add(data1)
        attempts.add(data2)

        for (i in 1..80) {
            _ripState.value = _ripState.value.copy(reReads = i, status = "Re-reading sector $lba (attempt $i)")

            if (capabilities.hasCache) {
                flushCache(fd, lba, capabilities, scsiDriver, endpointIn, endpointOut)
            }

            val newData = readSector(fd, lba, scsiDriver, endpointIn, endpointOut, capabilities.supportsC2) ?: continue
            attempts.add(newData)

            // Statistical majority: find most frequent
            val majority = findMajority(attempts)
            if (majority != null) {
                _ripState.value = _ripState.value.copy(reReads = 0)
                return if (capabilities.supportsC2) stripC2(majority) else majority
            }
        }

        _ripState.value = _ripState.value.copy(errorCount = _ripState.value.errorCount + 1)
        return if (capabilities.supportsC2) stripC2(data1) else data1 // Fallback
    }

    private fun flushCache(fd: Int, currentLba: Long, @Suppress("UNUSED_PARAMETER") capabilities: DriveCapabilities, scsiDriver: IScsiDriver, endpointIn: Int, endpointOut: Int) {
        // Read a sector far away (e.g. currentLba + 1000)
        // In a real implementation, this should be carefully chosen based on cache size
        val flushLba = currentLba + 1000
        readSector(fd, flushLba, scsiDriver, endpointIn, endpointOut, false)
    }

    private fun hasC2Errors(data: ByteArray): Boolean {
        // C2 pointers are 294 bytes after 2352 bytes of audio data
        // Each bit represents one byte of the 2352 bytes? No, each bit is one 16-bit sample?
        // 2352 bytes = 1176 samples. 1176 / 8 bits = 147 bytes.
        // Actually C2 pointers in READ CD are 294 bytes = 2352 bits. 1 bit per byte.
        for (i in 2352 until data.size) {
            if (data[i] != 0.toByte()) return true
        }
        return false
    }

    private fun stripC2(data: ByteArray): ByteArray {
        return data.copyOfRange(0, 2352)
    }

    private fun compareSectors(s1: ByteArray, s2: ByteArray): Boolean {
        return s1.contentEquals(s2)
    }

    private fun findMajority(attempts: List<ByteArray>): ByteArray? {
        val groups = mutableListOf<MutableList<ByteArray>>()
        for (attempt in attempts) {
            var found = false
            for (group in groups) {
                if (group[0].contentEquals(attempt)) {
                    group.add(attempt)
                    found = true
                    break
                }
            }
            if (!found) {
                groups.add(mutableListOf(attempt))
            }
        }

        for (group in groups) {
            if (group.size >= 10) { // Threshold for majority
                return group[0]
            }
        }
        return null
    }

    suspend fun detectCapabilities(
        fd: Int,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ): Result<DriveCapabilities> = withContext(Dispatchers.IO) {
        val detector = DriveCapabilityDetector(scsiDriver, driveOffsetService)
        detector.detect(fd, endpointIn, endpointOut)
    }

    suspend fun fullRip(
        context: Context,
        fd: Int,
        basePath: String,
        driveModel: String,
        capabilities: DriveCapabilities,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Reading TOC...")

        val toc = TocReader(scsiDriver).readToc(fd, endpointIn, endpointOut)
        if (toc == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        _ripState.value = _ripState.value.copy(totalTracks = toc.trackCount, status = "Found ${toc.trackCount} tracks")

        // Construct offsets array for legacy utils
        val trackOffsets = IntArray(100)
        toc.tracks.forEach { trackOffsets[it.number] = it.startLba }
        trackOffsets[0] = toc.leadOutLba

        // Calculate IDs and fetch metadata
        val arDiscId = toc.computeAccurateRipId()

        _ripState.value = _ripState.value.copy(status = "Fetching AccurateRip data...")
        val arData = accurateRipService.fetchAccurateRipData(toc.trackCount, arDiscId)

        val trackResults = mutableListOf<TrackRipResult>()

        val selectedMeta = _ripState.value.selectedMetadata ?: AlbumMetadata()

        for (track in toc.tracks) {
            if (!_ripState.value.isRunning) break
            val t = track.number

            val startLba = track.startLba.toLong()
            val totalTrackSectors = track.durationSectors

            if (totalTrackSectors <= 0) continue

            _ripState.value = _ripState.value.copy(
                currentTrack = t,
                totalSectors = totalTrackSectors.toLong(),
                currentSector = 0,
                status = "Ripping track $t: ${selectedMeta.tracks.getOrNull(t - 1) ?: "Track $t"}"
            )

            val sanitizedArtist = sanitizeFileName(selectedMeta.artist)
            val sanitizedAlbum = sanitizeFileName(selectedMeta.album)
            val sanitizedTitle = sanitizeFileName(selectedMeta.tracks.getOrNull(t - 1) ?: "Track $t")
            val fileName = "${t.toString().padStart(2, '0')} - $sanitizedTitle.flac"

            val relativePath = "$sanitizedArtist/$sanitizedAlbum/$fileName"
            val outputStream = getOutputStreamForPath(context, basePath, relativePath) ?: return@withContext

            flacEncoder.prepare(outputStream, 44100, 2)

            val crc32Generator = java.util.zip.CRC32()
            var trackArCrc = 0L

            for (sectorIndex in 0 until totalTrackSectors) {
                if (!_ripState.value.isRunning) break
                val lba = startLba + sectorIndex
                val sectorData = readSectorSecure(fd, lba, capabilities, scsiDriver, endpointIn, endpointOut)

                if (sectorData == null) {
                    _ripState.value = _ripState.value.copy(isRunning = false, status = "Fatal error at track $t, sector $sectorIndex")
                    return@withContext
                }

                flacEncoder.encode(sectorData)

                // Update CRCs
                crc32Generator.update(sectorData)
                trackArCrc = (trackArCrc + ChecksumUtils.calculateAccurateRipCrc(
                    sectorData, sectorIndex, totalTrackSectors, t == toc.firstTrack, t == toc.lastTrack
                )) and 0xFFFFFFFFL

                _ripState.value = _ripState.value.copy(
                    currentSector = sectorIndex.toLong() + 1,
                    progress = (sectorIndex + 1).toFloat() / totalTrackSectors,
                    status = "Secure Ripping track $t: sector ${sectorIndex + 1}/$totalTrackSectors"
                )
            }

            flacEncoder.finish()

            // Verify with AccurateRip
            val arMatches = arData[t]
            val arStatus = if (arMatches != null) {
                val match = arMatches.find { it.crc == trackArCrc || it.crc2 == trackArCrc }
                if (match != null) {
                    "Accurate (confidence ${match.confidence})"
                } else {
                    "Inaccurate (found ${arMatches.size} other pressings)"
                }
            } else {
                "Track not in database"
            }

            trackResults.add(TrackRipResult(
                trackNumber = t,
                status = "Success",
                reReads = 0, // Simplified for now
                crc32 = crc32Generator.value,
                accurateRipCrc = trackArCrc,
                accurateRipStatus = arStatus
            ))
        }

        // Finalize
        val ripSessionInfo = RipSessionInfo(
            appVersion = "1.0",
            date = java.util.Date(),
            driveModel = driveModel,
            capabilities = listOf(
                "C2: ${if (capabilities.supportsC2) "Yes" else "No"}",
                "Cache: ${if (capabilities.hasCache) "Yes" else "No"}"
            ),
            albumMetadata = selectedMeta,
            trackResults = trackResults
        )

        val logContent = LogGenerator.generateLog(ripSessionInfo)
        val sanitizedArtist = sanitizeFileName(selectedMeta.artist)
        val sanitizedAlbum = sanitizeFileName(selectedMeta.album)
        val logRelativePath = "$sanitizedArtist/$sanitizedAlbum/rip_log.txt"

        getOutputStreamForPath(context, basePath, logRelativePath)?.use { it.write(logContent.toByteArray()) }

        _ripState.value = _ripState.value.copy(isRunning = false, status = "Full Rip Complete", progress = 1f)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(":", "_").replace("*", "_").replace("?", "_").replace("/", "_").replace("\\", "_")
    }

    private fun getOutputStreamForPath(context: Context, basePath: String, relativePath: String? = null): java.io.OutputStream? {
        val fullPath = if (relativePath != null) "$basePath/$relativePath" else basePath

        return if (basePath.startsWith("content://")) {
            val rootUri = Uri.parse(basePath)
            var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null

            relativePath?.split("/")?.forEachIndexed { index, part ->
                if (part.isEmpty()) return@forEachIndexed
                val nextDoc = currentDoc.findFile(part)
                currentDoc = if (nextDoc == null) {
                    if (index == relativePath.split("/").lastIndex) {
                        currentDoc.createFile("application/octet-stream", part) ?: return null
                    } else {
                        currentDoc.createDirectory(part) ?: return null
                    }
                } else {
                    nextDoc
                }
            }
            context.contentResolver.openOutputStream(currentDoc.uri)
        } else {
            val file = File(fullPath)
            file.parentFile?.mkdirs()
            FileOutputStream(file)
        }
    }

    suspend fun ejectDisc(
        fd: Int,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        if (_ripState.value.isRunning) return@withContext
        _ripState.value = _ripState.value.copy(isTrayOperationInProgress = true, status = "Ejecting...")

        // START STOP UNIT (0x1B), LoEj=1, Start=0 (eject)
        val ejectCmd = byteArrayOf(0x1B, 0, 0, 0, 0x02, 0)
        scsiDriver.executeScsiCommand(fd, ejectCmd, 0, endpointIn, endpointOut)

        // Re-poll to update status immediately
        pollDriveStatus(fd, scsiDriver, endpointIn, endpointOut)
        _ripState.value = _ripState.value.copy(isTrayOperationInProgress = false)
    }

    suspend fun loadTray(
        fd: Int,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        if (_ripState.value.isRunning) return@withContext
        _ripState.value = _ripState.value.copy(isTrayOperationInProgress = true, status = "Loading Tray...")

        // START STOP UNIT (0x1B), LoEj=1, Start=1 (load)
        val loadCmd = byteArrayOf(0x1B, 0, 0, 0, 0x03, 0)
        scsiDriver.executeScsiCommand(fd, loadCmd, 0, endpointIn, endpointOut)

        // Re-poll to update status immediately
        pollDriveStatus(fd, scsiDriver, endpointIn, endpointOut)
        _ripState.value = _ripState.value.copy(isTrayOperationInProgress = false)
    }

    suspend fun pollDriveStatus(
        fd: Int,
        scsiDriver: IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        forceRefresh: Boolean = false
    ) = withContext(Dispatchers.IO) {
        if (_ripState.value.isRunning) return@withContext

        // TEST UNIT READY (0x00)
        val turCmd = byteArrayOf(0, 0, 0, 0, 0, 0)
        val response = scsiDriver.executeScsiCommand(fd, turCmd, 0, endpointIn, endpointOut)

        if (response != null) {
            _ripState.value = _ripState.value.copy(driveStatus = "Ready", tocError = null)

            if (_ripState.value.discToc == null || forceRefresh) {
                val toc = TocReader(scsiDriver).readToc(fd, endpointIn, endpointOut)
                if (toc != null) {
                    _ripState.value = _ripState.value.copy(discToc = toc)

                    // Fetch metadata
                    val trackOffsets = IntArray(100)
                    toc.tracks.forEach { trackOffsets[it.number] = it.startLba }
                    trackOffsets[0] = toc.leadOutLba
                    val discId = MusicBrainzUtils.calculateDiscId(toc.firstTrack, toc.lastTrack, trackOffsets)

                    val metadataList = metadataService.fetchMetadata(discId, toc)
                    _ripState.value = _ripState.value.copy(
                        availableMetadata = metadataList,
                        selectedMetadata = if (metadataList.size == 1) metadataList.first() else null
                    )
                } else {
                    _ripState.value = _ripState.value.copy(tocError = "Disc unreadable — try cleaning it")
                }
            }
        } else {
            // CHECK CONDITION -> REQUEST SENSE (0x03)
            val senseCmd = byteArrayOf(0x03, 0, 0, 0, 18, 0)
            val senseData = scsiDriver.executeScsiCommand(fd, senseCmd, 18, endpointIn, endpointOut)

            if (senseData != null && senseData.size >= 13) {
                val senseKey = senseData[2].toInt() and 0x0F
                val asc = senseData[12].toInt() and 0xFF

                val status = when {
                    senseKey == 0x02 && asc == 0x3A -> "No Disc / Tray Open"
                    senseKey == 0x02 && asc == 0x04 -> "Spinning Up"
                    senseKey == 0x02 -> "Not Ready"
                    else -> "Error (Key: $senseKey, ASC: $asc)"
                }
                val tocErr = if (senseKey == 0x02 && asc == 0x04) "Drive not ready yet — please wait"
                             else if (senseKey == 0x02 && asc == 0x3A) null
                             else "Drive error"
                _ripState.value = _ripState.value.copy(driveStatus = status, discToc = null, tocError = tocErr)
            } else {
                _ripState.value = _ripState.value.copy(driveStatus = "Communication Error", discToc = null)
            }
        }
    }


}
