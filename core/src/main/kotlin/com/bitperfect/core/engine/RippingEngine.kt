package com.bitperfect.core.engine

import com.bitperfect.core.utils.ChecksumUtils
import com.bitperfect.core.utils.MusicBrainzUtils
import com.bitperfect.driver.IScsiDriver
import com.bitperfect.driver.ScsiDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RipState(
    val isRunning: Boolean = false,
    val currentTrack: Int = 0,
    val totalTracks: Int = 0,
    val currentSector: Long = 0,
    val totalSectors: Long = 0,
    val progress: Float = 0f,
    val status: String = "Idle",
    val reReads: Int = 0,
    val errorCount: Int = 0
)

data class DriveCapabilities(
    val hasCache: Boolean = false,
    val cacheSizeKb: Int = 0,
    val supportsC2: Boolean = false
)

class RippingEngine(
    private val scsiDriver: IScsiDriver,
    private val flacEncoder: FlacEncoder = FlacEncoder(),
    private val metadataService: MetadataService = MetadataService(),
    private val accurateRipService: AccurateRipService = AccurateRipService()
) {
    private val _ripState = MutableStateFlow(RipState())
    val ripState: StateFlow<RipState> = _ripState.asStateFlow()

    suspend fun startBurstRip(
        fd: Int,
        outputPath: String,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Reading TOC...")

        val tocData = readToc(fd, endpointIn, endpointOut)
        if (tocData == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        val (firstTrack, lastTrack) = tocData
        val numTracks = lastTrack - firstTrack + 1
        _ripState.value = _ripState.value.copy(totalTracks = numTracks, status = "Found $numTracks tracks")

        // Simplified: Just rip the first track for now
        val startLba = 0L // Mocked
        val endLba = 50L   // Mocked small range for testing

        val totalSectors = endLba - startLba
        _ripState.value = _ripState.value.copy(totalSectors = totalSectors, currentTrack = 1)

        flacEncoder.prepare(outputPath, 44100, 2)

        for (lba in startLba until endLba) {
            val sectorData = readSector(fd, lba, endpointIn, endpointOut)
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
        fd: Int,
        outputPath: String,
        capabilities: DriveCapabilities,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Initializing Secure Rip...")

        val tocData = readToc(fd, endpointIn, endpointOut)
        if (tocData == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        val (firstTrack, lastTrack) = tocData
        val numTracks = lastTrack - firstTrack + 1
        _ripState.value = _ripState.value.copy(totalTracks = numTracks, status = "Found $numTracks tracks")

        val startLba = 0L // Mocked
        val endLba = 50L   // Mocked
        val totalSectors = endLba - startLba
        _ripState.value = _ripState.value.copy(totalSectors = totalSectors, currentTrack = 1)

        flacEncoder.prepare(outputPath, 44100, 2)

        for (lba in startLba until endLba) {
            val sectorData = readSectorSecure(fd, lba, capabilities, endpointIn, endpointOut)
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

    private fun readToc(fd: Int, endpointIn: Int, endpointOut: Int): Pair<Int, Int>? {
        val tocCmd = byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, 0, 18, 0)
        val tocResponse = scsiDriver.executeScsiCommand(fd, tocCmd, 18, endpointIn, endpointOut)
        if (tocResponse == null || tocResponse.size < 4) return null
        return Pair(tocResponse[2].toInt(), tocResponse[3].toInt())
    }

    private fun readSector(
        fd: Int,
        lba: Long,
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
        endpointIn: Int,
        endpointOut: Int
    ): ByteArray? {
        // Pass 1
        var data1 = readSector(fd, lba, endpointIn, endpointOut, capabilities.supportsC2) ?: return null

        // If C2 is supported, we can potentially trust it if no errors reported
        if (capabilities.supportsC2 && !hasC2Errors(data1)) {
            return stripC2(data1)
        }

        if (capabilities.hasCache) {
            flushCache(fd, lba, capabilities, endpointIn, endpointOut)
        }

        // Pass 2
        var data2 = readSector(fd, lba, endpointIn, endpointOut, capabilities.supportsC2) ?: return null

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
                flushCache(fd, lba, capabilities, endpointIn, endpointOut)
            }

            val newData = readSector(fd, lba, endpointIn, endpointOut, capabilities.supportsC2) ?: continue
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

    private fun flushCache(fd: Int, currentLba: Long, @Suppress("UNUSED_PARAMETER") capabilities: DriveCapabilities, endpointIn: Int, endpointOut: Int) {
        // Read a sector far away (e.g. currentLba + 1000)
        // In a real implementation, this should be carefully chosen based on cache size
        val flushLba = currentLba + 1000
        readSector(fd, flushLba, endpointIn, endpointOut, false)
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

    suspend fun fullRip(
        fd: Int,
        basePath: String,
        driveModel: String,
        capabilities: DriveCapabilities,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01
    ) = withContext(Dispatchers.IO) {
        _ripState.value = RipState(isRunning = true, status = "Reading TOC...")

        val tocResponse = readTocFull(fd, endpointIn, endpointOut)
        if (tocResponse == null) {
            _ripState.value = _ripState.value.copy(isRunning = false, status = "Failed to read TOC")
            return@withContext
        }

        val (firstTrack, lastTrack, trackOffsets) = tocResponse
        val numTracks = lastTrack - firstTrack + 1
        _ripState.value = _ripState.value.copy(totalTracks = numTracks, status = "Found $numTracks tracks")

        // Calculate IDs and fetch metadata
        val discId = MusicBrainzUtils.calculateDiscId(firstTrack, lastTrack, trackOffsets)
        _ripState.value = _ripState.value.copy(status = "Fetching metadata...")
        val metadata = metadataService.fetchMetadata(discId)

        val freeDbId = MusicBrainzUtils.calculateFreeDbId(
            trackOffsets.slice(1..lastTrack).map { it.toLong() }.toLongArray(),
            trackOffsets[0].toLong()
        )
        val arDiscId = accurateRipService.calculateAccurateRipDiscId(
            trackOffsets.slice(1..lastTrack).map { it.toLong() }.toLongArray(),
            trackOffsets[0].toLong(),
            freeDbId
        )

        _ripState.value = _ripState.value.copy(status = "Fetching AccurateRip data...")
        val arData = accurateRipService.fetchAccurateRipData(arDiscId)

        val trackResults = mutableListOf<TrackRipResult>()

        for (t in firstTrack..lastTrack) {
            val startLba = trackOffsets[t].toLong()
            val endLba = if (t < lastTrack) trackOffsets[t + 1].toLong() else trackOffsets[0].toLong()
            val totalTrackSectors = (endLba - startLba).toInt()

            _ripState.value = _ripState.value.copy(
                currentTrack = t,
                totalSectors = totalTrackSectors.toLong(),
                currentSector = 0,
                status = "Ripping track $t: ${metadata.tracks.getOrNull(t - 1) ?: "Track $t"}"
            )

            val trackPath = "${basePath}/${metadata.artist}/${metadata.album}/${t.toString().padStart(2, '0')} - ${metadata.tracks.getOrNull(t - 1) ?: "Track $t"}.flac"
            File(trackPath).parentFile?.mkdirs()
            flacEncoder.prepare(trackPath, 44100, 2)

            val crc32Generator = java.util.zip.CRC32()
            var trackArCrc = 0L

            for (sectorIndex in 0 until totalTrackSectors) {
                val lba = startLba + sectorIndex
                val sectorData = readSectorSecure(fd, lba, capabilities, endpointIn, endpointOut)

                if (sectorData == null) {
                    _ripState.value = _ripState.value.copy(isRunning = false, status = "Fatal error at track $t, sector $sectorIndex")
                    return@withContext
                }

                flacEncoder.encode(sectorData)

                // Update CRCs
                crc32Generator.update(sectorData)
                trackArCrc = (trackArCrc + ChecksumUtils.calculateAccurateRipCrc(
                    sectorData, sectorIndex, totalTrackSectors, t == firstTrack, t == lastTrack
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
            albumMetadata = metadata,
            trackResults = trackResults
        )

        val logContent = LogGenerator.generateLog(ripSessionInfo)
        LogGenerator.saveLogToFile("${basePath}/${metadata.artist}/${metadata.album}/rip_log.txt", logContent)

        _ripState.value = _ripState.value.copy(isRunning = false, status = "Full Rip Complete", progress = 1f)
    }

    private fun readTocFull(fd: Int, endpointIn: Int, endpointOut: Int): Triple<Int, Int, IntArray>? {
        // READ TOC command (0x43), format 0 (standard TOC)
        val tocCmd = byteArrayOf(0x43, 0, 0, 0, 0, 0, 0, 0x03.toByte(), 0x24.toByte(), 0)
        // We need more data for full TOC. Each entry is 8 bytes. Max 100 tracks + leadout.
        val tocResponse = scsiDriver.executeScsiCommand(fd, tocCmd, 804, endpointIn, endpointOut)
        if (tocResponse == null || tocResponse.size < 4) return null

        val firstTrack = tocResponse[2].toInt() and 0xFF
        val lastTrack = tocResponse[3].toInt() and 0xFF
        val offsets = IntArray(100)

        for (i in 0 until (lastTrack - firstTrack + 2)) {
            val base = 4 + i * 8
            if (base + 8 > tocResponse.size) break
            val trackNum = tocResponse[base + 2].toInt() and 0xFF
            val lba = ((tocResponse[base + 4].toInt() and 0xFF) shl 24) or
                      ((tocResponse[base + 5].toInt() and 0xFF) shl 16) or
                      ((tocResponse[base + 6].toInt() and 0xFF) shl 8) or
                      (tocResponse[base + 7].toInt() and 0xFF)

            if (trackNum == 0xAA) { // Lead-out
                offsets[0] = lba
            } else if (trackNum in 1..99) {
                offsets[trackNum] = lba
            }
        }

        return Triple(firstTrack, lastTrack, offsets)
    }

}
