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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.net.URL
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.InputStream

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
    val estimatedCacheSizeSectors: Int = 0,
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
    var onLog: ((String) -> Unit)? = null
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
        _ripState.value = _ripState.value.copy(isRunning = true, status = "Reading TOC...")

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

        val sectorReader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)
        val outputStream = getOutputStreamForPath(context, outputPath) ?: run {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to open output stream")
            return@withContext
        }
        flacEncoder.prepare(outputStream, 44100, 2)

        for (lba in startLba until endLba) {
            val sectorData = sectorReader.readSectors(lba)
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
        _ripState.value = _ripState.value.copy(isRunning = true, status = "Initializing Secure Rip...")

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

        val outputStream = getOutputStreamForPath(context, outputPath) ?: run {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to open output stream")
            return@withContext
        }
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



    private fun readSectorSecure(
        fd: Int,
        lba: Long,
        capabilities: DriveCapabilities,
        scsiDriver: IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        sectorReader: CdSectorReader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)
    ): ByteArray? {
        // Pass 1
        var data1 = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: return null

        // If C2 is supported, we can potentially trust it if no errors reported
        if (capabilities.supportsC2 && !hasC2Errors(data1)) {
            return stripC2(data1)
        }

        if (capabilities.hasCache) {
            flushCache(fd, lba, capabilities, scsiDriver, endpointIn, endpointOut)
        }

        // Pass 2
        var data2 = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: return null

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

            val newData = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false) ?: continue
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
        val sectorReader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)
        sectorReader.readSectors(flushLba, 1, false, false)
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


    suspend fun calibrateOffset(
        fd: Int,
        capabilities: DriveCapabilities,
        scsiDriver: IScsiDriver = defaultScsiDriver,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ): Result<Int> = withContext(Dispatchers.IO) {
        _ripState.value = _ripState.value.copy(isRunning = true, status = "Initializing Calibration...")

        val toc = TocReader(scsiDriver).readToc(fd, endpointIn, endpointOut)
        if (toc == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext Result.failure(Exception("Failed to read TOC"))
        }

        val firstAudioTrack = toc.tracks.firstOrNull { it.isAudio }
        if (firstAudioTrack == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "No audio tracks found")
            return@withContext Result.failure(Exception("No audio tracks found"))
        }

        val arDiscId = toc.computeAccurateRipId()
        _ripState.value = _ripState.value.copy(status = "Fetching AccurateRip data for calibration...")
        val arData = accurateRipService.fetchAccurateRipData(toc.trackCount, arDiscId, onLog)

        val trackNumber = firstAudioTrack.number
        val trackMatches = arData[trackNumber]
        if (trackMatches.isNullOrEmpty()) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Disc not found in AccurateRip database")
            return@withContext Result.failure(Exception("Disc not found in AccurateRip database. Try a different disc."))
        }

        val startLba = firstAudioTrack.startLba.toLong()
        val totalTrackSectors = firstAudioTrack.durationSectors

        // We will rip the track into memory (or calculate rolling CRCs)
        // Rip the entire track + 5 sectors padding at the start and end (if possible).
        // Since we want to find the offset, let's rip it normally but keep it in memory
        // and calculate the shifted CRCs.
        // Reading a whole track into memory might be too large (e.g. 5 mins = 50MB).
        // Instead, we can read sectors and calculate the base CRC for offsets -5 to +5 sectors (-2940 to +2940 samples).
        // Let's implement a simpler rolling CRC or just calculate it for each offset.

        val scanSectors = 5
        val samplesPerSector = 588
        val minOffset = -scanSectors * samplesPerSector
        val maxOffset = scanSectors * samplesPerSector
        val totalOffsets = maxOffset - minOffset + 1

        val isFirstTrack = trackNumber == toc.firstTrack
        val isLastTrack = trackNumber == toc.lastTrack

        _ripState.value = _ripState.value.copy(status = "Ripping track $trackNumber for calibration...")

        val crcAccumulators = LongArray(totalOffsets) { 0L }
        val windowSize = scanSectors * 2 + 1
        val circularBuffer = Array(windowSize) { ByteArray(2352) }
        var bufferHead = 0

        // Pre-fill the buffer with the first `scanSectors` sectors of padding (zeros)
        // and then the first `scanSectors + 1` sectors of actual data.
        for (i in -scanSectors..scanSectors) {
            if (!_ripState.value.isRunning) return@withContext Result.failure(Exception("Cancelled"))

            val lba = startLba + i
            val data = if (lba >= 0 && i < totalTrackSectors) {
                val sectorReader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)
                val sectorData = sectorReader.readSectors(lba, 1, capabilities.supportsC2, false)
                sectorData?.let { if (capabilities.supportsC2) stripC2(it) else it } ?: ByteArray(2352)
            } else {
                ByteArray(2352)
            }
            circularBuffer[bufferHead] = data
            bufferHead = (bufferHead + 1) % windowSize
        }

        val tempSector = ByteArray(2352)

        for (sectorIndex in 0 until totalTrackSectors) {
            if (!_ripState.value.isRunning) return@withContext Result.failure(Exception("Cancelled"))

            // For each offset, construct the shifted sector and update its CRC
            for (offsetIndex in 0 until totalOffsets) {
                val offsetSamples = minOffset + offsetIndex
                val shiftBytes = offsetSamples * 4

                // Which buffer index contains the start of this shifted sector?
                // Offset is relative to the center of the window (which is the current `sectorIndex`).
                // The center of the window in the circular buffer is `(bufferHead + scanSectors) % windowSize`.
                // Actually, the window holds sectors `sectorIndex - scanSectors` to `sectorIndex + scanSectors`.
                // The oldest sector is at `bufferHead`. It corresponds to `sectorIndex - scanSectors`.

                val sectorOffsetBytes = scanSectors * 2352 + shiftBytes
                val startBufferIdx = sectorOffsetBytes / 2352
                val startByteInBuff = sectorOffsetBytes % 2352

                val idx1 = (bufferHead + startBufferIdx) % windowSize
                val data1 = circularBuffer[idx1]

                if (startByteInBuff == 0) {
                    crcAccumulators[offsetIndex] = (crcAccumulators[offsetIndex] + ChecksumUtils.calculateAccurateRipCrc(
                        data1, sectorIndex, totalTrackSectors, isFirstTrack, isLastTrack
                    )) and 0xFFFFFFFFL
                } else {
                    val idx2 = (bufferHead + startBufferIdx + 1) % windowSize
                    val data2 = circularBuffer[idx2]

                    System.arraycopy(data1, startByteInBuff, tempSector, 0, 2352 - startByteInBuff)
                    System.arraycopy(data2, 0, tempSector, 2352 - startByteInBuff, startByteInBuff)

                    crcAccumulators[offsetIndex] = (crcAccumulators[offsetIndex] + ChecksumUtils.calculateAccurateRipCrc(
                        tempSector, sectorIndex, totalTrackSectors, isFirstTrack, isLastTrack
                    )) and 0xFFFFFFFFL
                }
            }

            // Advance the window: read the next sector (sectorIndex + scanSectors + 1)
            val nextLbaOffset = sectorIndex + scanSectors + 1
            val nextLba = startLba + nextLbaOffset
            val nextData = if (nextLba >= 0 && nextLbaOffset < totalTrackSectors) {
                val sectorReader = CdSectorReader(scsiDriver, fd, endpointIn, endpointOut)
                val sectorData = sectorReader.readSectors(nextLba, 1, capabilities.supportsC2, false)
                sectorData?.let { if (capabilities.supportsC2) stripC2(it) else it } ?: ByteArray(2352)
            } else {
                ByteArray(2352)
            }

            circularBuffer[bufferHead] = nextData
            bufferHead = (bufferHead + 1) % windowSize

            if (sectorIndex % 100 == 0) {
                _ripState.value = _ripState.value.copy(
                    progress = sectorIndex.toFloat() / totalTrackSectors,
                    status = "Ripping track $trackNumber... ($sectorIndex/$totalTrackSectors)"
                )
            }
        }

        // Check if any CRC matches
        for (offsetIndex in 0 until totalOffsets) {
            val crc = crcAccumulators[offsetIndex]
            if (trackMatches.any { it.crc == crc || it.crc2 == crc }) {
                val matchedOffset = minOffset + offsetIndex
                _ripState.value = _ripState.value.copy(isRunning = false, status = "Calibration complete", progress = 1f)
                return@withContext Result.success(matchedOffset)
            }
        }

        _ripState.value = _ripState.value.copy(isRunning = false, status = "No match found")
        Result.failure(Exception("Could not find a matching offset within ±5 sectors. Try manual entry or a different reference disc."))
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
        _ripState.value = _ripState.value.copy(isRunning = true, status = "Reading TOC...")

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
        val arData = accurateRipService.fetchAccurateRipData(toc.trackCount, arDiscId, onLog)

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
            val outputStream = getOutputStreamForPath(context, basePath, relativePath) ?: run {
                _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to open output stream")
                return@withContext
            }

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

            // Embed metadata
            embedMetadata(context, basePath, relativePath, selectedMeta, t)

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

                    val metadataList = metadataService.fetchMetadata(discId, toc, onLog)
                    val arDiscId = toc.computeAccurateRipId()
                    accurateRipService.fetchAccurateRipData(toc.trackCount, arDiscId, onLog)
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
                    senseKey == 0x06 && asc == 0x28 -> "Media Changed"
                    senseKey == 0x06 -> "Unit Attention"
                    else -> "Error (Key: $senseKey, ASC: $asc)"
                }
                val tocErr = if (senseKey == 0x02 && asc == 0x04) "Drive not ready yet — please wait"
                             else if (senseKey == 0x02 && asc == 0x3A) null
                             else if (senseKey == 0x06) null
                             else "Drive error"
                _ripState.value = _ripState.value.copy(driveStatus = status, discToc = null, tocError = tocErr)
            } else {
                _ripState.value = _ripState.value.copy(driveStatus = "Communication Error", discToc = null)
            }
        }
    }


    private suspend fun embedMetadata(
        context: Context,
        basePath: String,
        relativePath: String,
        metadata: AlbumMetadata,
        trackNumber: Int
    ) = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val file = getFileForPath(context, basePath, relativePath) ?: return@withContext
            tempFile = if (basePath.startsWith("content://")) file else null

            val af = AudioFileIO.read(file)
            val tag = af.tagOrCreateAndSetDefault
            tag.setField(FieldKey.ARTIST, metadata.artist)
            tag.setField(FieldKey.ALBUM, metadata.album)
            tag.setField(FieldKey.YEAR, metadata.year)
            tag.setField(FieldKey.TRACK, trackNumber.toString())
            val title = metadata.tracks.getOrNull(trackNumber - 1) ?: "Track $trackNumber"
            tag.setField(FieldKey.TITLE, title)

            if (metadata.albumArtUrl != null) {
                try {
                    val url = java.net.URL(metadata.albumArtUrl)
                    val connection = url.openConnection()
                    connection.setRequestProperty("User-Agent", "BitPerfect/1.0")
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    val artwork = ArtworkFactory.getNew()
                    artwork.binaryData = bytes
                    artwork.mimeType = "image/jpeg"
                    tag.setField(artwork)
                } catch (e: Exception) {
                    Log.e("RippingEngine", "Failed to embed artwork", e)
                }
            }

            af.commit()

            // if it was content uri, write back
            if (basePath.startsWith("content://")) {
                val pfd = getFileDescriptorForPath(context, basePath, relativePath, "w")
                if (pfd != null) {
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        FileInputStream(file).use { input ->
                            input.copyTo(out)
                        }
                    }
                    pfd.close()
                }
            }
        } catch (e: Exception) {
            Log.e("RippingEngine", "Failed to embed metadata", e)
        } finally {
            tempFile?.delete()
        }
    }

    private fun getFileForPath(context: Context, basePath: String, relativePath: String): File? {
        val fullPath = "$basePath/$relativePath"
        if (basePath.startsWith("content://")) {
            val pfd = getFileDescriptorForPath(context, basePath, relativePath, "r") ?: return null
            val tempFile = File.createTempFile("temp_flac", ".flac", context.cacheDir)
            FileInputStream(pfd.fileDescriptor).use { input ->
                FileOutputStream(tempFile).use { out ->
                    input.copyTo(out)
                }
            }
            pfd.close()
            return tempFile
        } else {
            return File(fullPath)
        }
    }

    private fun getFileDescriptorForPath(context: Context, basePath: String, relativePath: String, mode: String): ParcelFileDescriptor? {
        val rootUri = Uri.parse(basePath)
        var currentDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null

        relativePath.split("/").forEach { part ->
            if (part.isEmpty()) return@forEach
            val nextDoc = currentDoc.findFile(part)
            currentDoc = nextDoc ?: return null
        }
        return context.contentResolver.openFileDescriptor(currentDoc.uri, mode)
    }
}
