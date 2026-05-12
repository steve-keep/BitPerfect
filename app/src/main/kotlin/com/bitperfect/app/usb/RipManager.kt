package com.bitperfect.app.usb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.app.BuildConfig
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.services.AccurateRipService
import com.bitperfect.core.services.AccurateRipVerifier
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.services.DriveOffsetRepository
import com.bitperfect.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream

import com.bitperfect.core.utils.computeAccurateRipDiscId
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.BufferedReader

enum class RipStatus {
    IDLE, RIPPING, VERIFYING, SUCCESS, UNVERIFIED, WARNING, ERROR, CANCELLED
}

data class TrackRipState(
    val trackNumber: Int,
    val progress: Float = 0f,
    val status: RipStatus = RipStatus.IDLE,
    val accurateRipUrl: String? = null,
    val computedChecksum: Long? = null,
    val expectedChecksums: List<Long> = emptyList(),
    val errorMessage: String? = null,
    // Diagnostic fields
    val startLba: Int = 0,
    val endLba: Int = 0,
    val totalSectors: Int = 0,
    val sectorsRead: Int = 0,
    val totalSamples: Long = 0L,
    val durationSeconds: Double = 0.0
)

class RipManager(
    private val context: Context,
    private val outputFolderUriString: String,
    private val toc: DiscToc,
    private val metadata: DiscMetadata,
    private val expectedChecksums: Map<Int, List<AccurateRipTrackMetadata>>,
    private val artworkBytes: ByteArray?,
    private val driveVendor: String,
    private val driveProduct: String,
    initialTracks: List<Int>
) {
    private val _trackStates = MutableStateFlow<Map<Int, TrackRipState>>(
        toc.tracks.associate { it.trackNumber to TrackRipState(it.trackNumber) }
    )
    val trackStates: StateFlow<Map<Int, TrackRipState>> = _trackStates

    private var isCancelled = false
    private val verifier = AccurateRipVerifier()

    private var albumDir: DocumentFile? = null

    private val trackQueue = java.util.concurrent.ConcurrentLinkedQueue(initialTracks)

    fun queueTrack(trackNumber: Int) {
        if (!trackQueue.contains(trackNumber)) {
            trackQueue.offer(trackNumber)
            updateTrackState(trackNumber, RipStatus.IDLE, 0f)
        }
    }

    suspend fun startRipping(session: UsbReadSession) = withContext(Dispatchers.IO) {
        val driveOffset: Int = try {
            DriveOffsetRepository(context).findOffset(driveVendor, driveProduct)?.offset ?: 0
        } catch (e: Exception) {
            AppLogger.w("RipManager", "Could not determine drive offset, defaulting to 0: ${e.message}")
            0
        }
        AppLogger.d("RipManager", "Drive offset: $driveOffset samples")

        val baseUri = Uri.parse(outputFolderUriString)
        val parentDir = DocumentFile.fromTreeUri(context, baseUri)
        if (parentDir == null || !parentDir.exists() || !parentDir.isDirectory) {
            AppLogger.e("RipManager", "Invalid output directory")
            return@withContext
        }

        // Create Artist/Album structure
        val safeArtist = metadata.artistName.replace("/", "_")
        val safeAlbum = metadata.albumTitle.replace("/", "_")

        val artistDir = parentDir.findFile(safeArtist) ?: parentDir.createDirectory(safeArtist)
        if (artistDir == null) {
            AppLogger.e("RipManager", "Could not create artist directory")
            return@withContext
        }

        albumDir = artistDir.findFile(safeAlbum) ?: artistDir.createDirectory(safeAlbum)
        if (albumDir == null) {
            AppLogger.e("RipManager", "Could not create album directory")
            return@withContext
        }

        val accurateRipUrl = AccurateRipService().getAccurateRipUrl(toc)

        val tocOffset = run {
            var toc = driveOffset / 588
            var rem = driveOffset % 588
            if (rem < 0) { rem += 588; toc-- }
            toc
        }
        val sampleOffset = run {
            var rem = driveOffset % 588
            if (rem < 0) rem += 588
            rem
        }
        val skipBytes = sampleOffset * 4
        var overreadBuffer: ByteArray? = null

        while (trackQueue.isNotEmpty()) {
            if (isCancelled) break

            val trackNumber = trackQueue.poll() ?: break

            val i = trackNumber - 1
            if (i < 0 || i >= toc.tracks.size) continue

            val entry = toc.tracks[i]
            val trackTitle = metadata.trackTitles.getOrNull(i) ?: "Track $trackNumber"
            val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
            val totalSectors = nextLba - entry.lba
            val totalSamples = totalSectors.toLong() * 588L

            updateTrackState(trackNumber, RipStatus.RIPPING, 0f)

            if (isCancelled) break

            val safeTitle = trackTitle.replace("/", "_")
            val filename = String.format("%02d - %s.flac", trackNumber, safeTitle)

            albumDir?.findFile(filename)?.delete()
            val destFile = albumDir?.createFile("audio/flac", filename)
            if (destFile == null) {
                AppLogger.e("RipManager", "Failed to create SAF destination for track $trackNumber")
                updateTrackState(
                    trackNumber = trackNumber,
                    status = RipStatus.ERROR,
                    progress = 0f,
                    errorMessage = "Failed to create destination file",
                    startLba = entry.lba,
                    endLba = nextLba,
                    totalSectors = totalSectors,
                    sectorsRead = 0,
                    totalSamples = totalSamples,
                    durationSeconds = 0.0
                )
                continue
            }

            var outputStream: java.io.OutputStream? = null
            var encoder: FlacEncoder? = null
            var finalChecksum = 0L
            var sectorsRead = 0

            try {
                val rawStream = context.contentResolver.openOutputStream(destFile.uri)
                    ?: throw java.io.IOException("Cannot open SAF output stream")
                outputStream = BufferedOutputStream(rawStream, 1024 * 1024)

                val metadataBytes = buildFlacMetadata(
                    totalSamples = totalSamples,
                    artist = metadata.artistName,
                    album = metadata.albumTitle,
                    title = trackTitle,
                    track = trackNumber,
                    year = metadata.year,
                    genre = metadata.genre,
                    albumArtist = metadata.albumArtist,
                    mbReleaseId = metadata.mbReleaseId,
                    accurateRipUrl = accurateRipUrl,
                    artworkBytes = artworkBytes
                )
                outputStream.write(metadataBytes)

                encoder = FlacEncoder(outputStream, writeHeader = false)
                encoder.start()

                val isFirstTrack = (i == 0)
                val isLastTrack  = (i == toc.tracks.size - 1)

                val checksumAccumulator = ChecksumAccumulator(
                    verifier      = verifier,
                    totalSamples  = totalSamples,
                    isFirstTrack  = isFirstTrack,
                    isLastTrack   = isLastTrack
                )

                val chunkSize = 8 // read ~8 sectors at a time
                val lbaStart = entry.lba + tocOffset

                val (firstLba, lastReadableLba) = ripLbaRange(
                    trackLba      = entry.lba,
                    nextLba       = nextLba,
                    tocOffset     = tocOffset,
                    pregapOffset  = toc.pregapOffset,
                    isLastTrack   = isLastTrack
                )

                var isFirstSector = true

                if (overreadBuffer != null) {
                    encoder.encode(overreadBuffer!!)
                    checksumAccumulator.accumulate(overreadBuffer!!)
                    sectorsRead = 1
                    isFirstSector = false
                }

                val effectiveTotalSectors = lastReadableLba - firstLba + 1

                while (sectorsRead < effectiveTotalSectors && !isCancelled) {
                    val sectorsToRead = minOf(chunkSize, effectiveTotalSectors - sectorsRead)
                    val pcmData = session.readSectors(firstLba + sectorsRead, sectorsToRead)

                    if (pcmData != null) {
                        val sectorsActuallyRead = pcmData.size / 2352
                        if (sectorsActuallyRead < sectorsToRead) {
                            AppLogger.w("RipManager", "Short read at LBA ${firstLba + sectorsRead}: " +
                                "got $sectorsActuallyRead of $sectorsToRead sectors")
                        }

                        val trimmed = if (isFirstSector && skipBytes > 0) pcmData.copyOfRange(skipBytes, pcmData.size) else pcmData
                        encoder.encode(trimmed)
                        checksumAccumulator.accumulate(trimmed)
                        isFirstSector = false

                        sectorsRead += sectorsActuallyRead
                    } else {
                        if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                            isCancelled = true
                            throw java.io.IOException("Disc removed or drive not ready during rip")
                        }
                        throw java.io.IOException("Failed to read sector ${firstLba + sectorsRead} after 3 attempts") // see UsbReadSession.MAX_RETRIES
                    }

                    updateTrackState(trackNumber, RipStatus.RIPPING, sectorsRead.toFloat() / effectiveTotalSectors)
                }

                if (sampleOffset > 0) {
                    if (!isLastTrack) {
                        val overreadPcm = session.readSectors(lbaStart + totalSectors - toc.pregapOffset, 1)
                        if (overreadPcm == null) {
                            if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                                isCancelled = true
                                throw java.io.IOException("Disc removed or drive not ready during rip")
                            }
                            throw java.io.IOException("Failed to read overshoot sector ${lbaStart + totalSectors} after 3 attempts") // see UsbReadSession.MAX_RETRIES
                        }
                        val toFeed = overreadPcm.copyOfRange(0, skipBytes)
                        encoder.encode(toFeed)
                        checksumAccumulator.accumulate(toFeed)
                        overreadBuffer = overreadPcm.copyOfRange(skipBytes, overreadPcm.size)
                    } else {
                        val silence = ByteArray(skipBytes)
                        encoder.encode(silence)
                        checksumAccumulator.accumulate(silence)
                        overreadBuffer = null
                    }
                } else {
                    overreadBuffer = null
                }

                if (isLastTrack && tocOffset > 0) {
                    val silence = ByteArray(tocOffset * 2352)
                    encoder.encode(silence)
                    checksumAccumulator.accumulate(silence)
                }

                encoder.stop()

                updateTrackState(
                    trackNumber = trackNumber,
                    status = RipStatus.RIPPING,
                    progress = sectorsRead.toFloat() / totalSectors,
                    startLba = entry.lba,
                    endLba = nextLba,
                    totalSectors = totalSectors,
                    sectorsRead = sectorsRead,
                    totalSamples = totalSamples,
                    durationSeconds = sectorsRead.toLong() * 588L / 44100.0
                )

                finalChecksum = checksumAccumulator.finalise()
            } catch (e: Exception) {
                AppLogger.e("RipManager", "Error ripping track $trackNumber", e)
                updateTrackState(
                    trackNumber = trackNumber,
                    status = RipStatus.ERROR,
                    progress = sectorsRead.toFloat() / totalSectors,
                    errorMessage = e.message ?: "Unknown error",
                    startLba = entry.lba,
                    endLba = nextLba,
                    totalSectors = totalSectors,
                    sectorsRead = sectorsRead,
                    totalSamples = totalSamples,
                    durationSeconds = sectorsRead.toLong() * 588L / 44100.0
                )
                continue
            } finally {
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }

            updateTrackState(trackNumber, RipStatus.VERIFYING, 1f)

            // Verify checksum
            val expectedForTrack = expectedChecksums[trackNumber]

            when {
                expectedForTrack == null -> {
                    AppLogger.d("RipManager", "Track $trackNumber not in AccurateRip database.")
                    updateTrackState(trackNumber, RipStatus.UNVERIFIED, 1f)
                }
                expectedForTrack.any { it.checksum == finalChecksum } -> {
                    updateTrackState(trackNumber, RipStatus.SUCCESS, 1f)
                }
                else -> {
                    AppLogger.w("RipManager", "Checksum mismatch for track $trackNumber.")
                    updateTrackState(
                        trackNumber,
                        RipStatus.WARNING,
                        1f,
                        accurateRipUrl = accurateRipUrl,
                        computedChecksum = finalChecksum,
                        expectedChecksums = expectedForTrack.map { it.checksum }
                    )
                }
            }

            val currentState = _trackStates.value[trackNumber]
            if (currentState != null) {
                writeAccurateRipJsonl(albumDir, currentState)
            }
        }

        if (!isCancelled) {
            writeRipLog(albumDir, driveOffset, _trackStates.value)
        }
    }

    private fun writeAccurateRipJsonl(albumDir: DocumentFile?, state: TrackRipState) {
        val dir = albumDir ?: return
        try {
            var file = dir.findFile("BitPerfect.jsonl")
            val existingLines = mutableListOf<JSONObject>()

            if (file != null) {
                context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.forEachLine { line ->
                            if (line.isNotBlank()) {
                                try {
                                    existingLines.add(JSONObject(line))
                                } catch (e: Exception) {
                                    // Ignore malformed lines
                                }
                            }
                        }
                    }
                }
            } else {
                file = dir.createFile("application/jsonl", "BitPerfect.jsonl")
            }

            if (file == null) return

            val isVerified = state.status == RipStatus.SUCCESS

            // Remove existing entry for the same disc and track
            existingLines.removeAll {
                it.optInt("disc", -1) == 1 && it.optInt("track", -1) == state.trackNumber
            }

            val newEntry = JSONObject()
            newEntry.put("disc", 1)
            newEntry.put("track", state.trackNumber)

            val accurateRipObj = JSONObject()
            accurateRipObj.put("isVerified", isVerified)
            if (state.computedChecksum != null) {
                val computedStr = String.format("0x%08X", state.computedChecksum and 0xFFFFFFFFL)
                accurateRipObj.put("checksum", computedStr)
            }
            newEntry.put("accurateRip", accurateRipObj)

            existingLines.add(newEntry)

            context.contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
                existingLines.forEach { obj ->
                    outputStream.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("RipManager", "Failed to write BitPerfect.jsonl", e)
        }
    }

    private fun updateTrackState(
        trackNumber: Int,
        status: RipStatus,
        progress: Float,
        accurateRipUrl: String? = null,
        computedChecksum: Long? = null,
        expectedChecksums: List<Long> = emptyList(),
        errorMessage: String? = null,
        startLba: Int? = null,
        endLba: Int? = null,
        totalSectors: Int? = null,
        sectorsRead: Int? = null,
        totalSamples: Long? = null,
        durationSeconds: Double? = null
    ) {
        val currentStates = _trackStates.value.toMutableMap()
        val existingState = currentStates[trackNumber] ?: TrackRipState(trackNumber)

        currentStates[trackNumber] = existingState.copy(
            progress = progress,
            status = status,
            accurateRipUrl = accurateRipUrl ?: existingState.accurateRipUrl,
            computedChecksum = computedChecksum ?: existingState.computedChecksum,
            expectedChecksums = if (expectedChecksums.isNotEmpty()) expectedChecksums else existingState.expectedChecksums,
            errorMessage = errorMessage ?: existingState.errorMessage,
            startLba = startLba ?: existingState.startLba,
            endLba = endLba ?: existingState.endLba,
            totalSectors = totalSectors ?: existingState.totalSectors,
            sectorsRead = sectorsRead ?: existingState.sectorsRead,
            totalSamples = totalSamples ?: existingState.totalSamples,
            durationSeconds = durationSeconds ?: existingState.durationSeconds
        )
        _trackStates.value = currentStates
    }

    fun cancel() {
        isCancelled = true
    }

    fun deleteRipFiles() {
        try {
            albumDir?.delete()
        } catch (e: Exception) {
            AppLogger.e("RipManager", "Failed to delete album directory", e)
        }
    }


    private fun buildFlacMetadata(
        totalSamples: Long,
        artist: String?,
        album: String?,
        title: String?,
        track: Int,
        year: String?,
        genre: String?,
        albumArtist: String?,
        mbReleaseId: String?,
        accurateRipUrl: String?,
        artworkBytes: ByteArray?
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // fLaC
        out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))

        // STREAMINFO (type 0, length 34 = 0x22)
        val hasPicture = artworkBytes != null
        out.write(0x00) // Type 0, last = false
        out.write(byteArrayOf(0x00, 0x00, 0x22))

        val streamInfo = java.nio.ByteBuffer.allocate(34)
        streamInfo.putShort(4096) // min block size
        streamInfo.putShort(4096) // max block size
        streamInfo.put(byteArrayOf(0, 0, 0)) // min frame size
        streamInfo.put(byteArrayOf(0, 0, 0)) // max frame size

        // sample rate 44100 (20 bits), channels 2 (3 bits), bps 16 (5 bits), total samples (36 bits)
        val sr = 44100
        val ch = 2 - 1
        val bps = 16 - 1

        val b1 = (sr shr 12) and 0xFF
        val b2 = (sr shr 4) and 0xFF
        val b3 = ((sr and 0xF) shl 4) or (ch shl 1) or ((bps shr 4) and 0x1)
        val b4 = ((bps and 0xF) shl 4) or ((totalSamples shr 32).toInt() and 0xF)
        val b5 = (totalSamples shr 24).toInt() and 0xFF
        val b6 = (totalSamples shr 16).toInt() and 0xFF
        val b7 = (totalSamples shr 8).toInt() and 0xFF
        val b8 = totalSamples.toInt() and 0xFF

        streamInfo.put(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte(), b5.toByte(), b6.toByte(), b7.toByte(), b8.toByte()))
        streamInfo.put(ByteArray(16)) // MD5 zeroed
        out.write(streamInfo.array())

        // VORBIS_COMMENT (type 4)
        val isLast = !hasPicture
        val vcType = if (isLast) 0x84 else 0x04

        val vcPayload = ByteArrayOutputStream()
        val vendorString = "BitPerfect".toByteArray(Charsets.UTF_8)
        vcPayload.writeLittleEndianInt(vendorString.size)
        vcPayload.write(vendorString)

        val comments = mutableListOf<String>()
        if (artist != null) comments.add("ARTIST=$artist")
        if (album != null) comments.add("ALBUM=$album")
        if (title != null) comments.add("TITLE=$title")
        comments.add("TRACKNUMBER=$track")
        if (year != null) comments.add("DATE=$year")
        if (genre != null) comments.add("GENRE=$genre")
        if (albumArtist != null) comments.add("ALBUMARTIST=$albumArtist")
        if (mbReleaseId != null) comments.add("MUSICBRAINZ_ALBUMID=$mbReleaseId")
        if (accurateRipUrl != null) {
            // URL format: http://.../dBAR-010-000bba6a-006fbb59-71089d0a.bin
            // We want the IDs part: 010-000bba6a-006fbb59-71089d0a
            val regex = "dBAR-([^.]+)\\.bin".toRegex()
            val match = regex.find(accurateRipUrl)
            if (match != null) {
                comments.add("ACCURATERIPDISCID=${match.groupValues[1]}")
            }
        }
        comments.add("BITDEPTH=16")
        comments.add("SAMPLERATE=44100")
        comments.add("COMMENT=Ripped with BitPerfect")

        vcPayload.writeLittleEndianInt(comments.size)
        for (comment in comments) {
            val bytes = comment.toByteArray(Charsets.UTF_8)
            vcPayload.writeLittleEndianInt(bytes.size)
            vcPayload.write(bytes)
        }

        val vcBytes = vcPayload.toByteArray()
        out.write(vcType)
        out.write(byteArrayOf((vcBytes.size shr 16).toByte(), (vcBytes.size shr 8).toByte(), vcBytes.size.toByte()))
        out.write(vcBytes)

        if (hasPicture) {
            val picType = 0x86 // Last block = true, type 6
            val mimeType = detectMimeType(artworkBytes!!)
            val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
            val picPayload = ByteArrayOutputStream()

            picPayload.writeBigEndianInt(3) // Picture type: Front cover
            picPayload.writeBigEndianInt(mimeBytes.size)
            picPayload.write(mimeBytes)
            picPayload.writeBigEndianInt(0) // Description length
            picPayload.writeBigEndianInt(0) // width
            picPayload.writeBigEndianInt(0) // height
            picPayload.writeBigEndianInt(0) // color depth
            picPayload.writeBigEndianInt(0) // indexed colors
            picPayload.writeBigEndianInt(artworkBytes.size)
            picPayload.write(artworkBytes)

            val picBytes = picPayload.toByteArray()
            out.write(picType)
            out.write(byteArrayOf((picBytes.size shr 16).toByte(), (picBytes.size shr 8).toByte(), picBytes.size.toByte()))
            out.write(picBytes)
        }

        return out.toByteArray()
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        return "image/jpeg"
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeBigEndianInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun writeRipLog(albumDir: DocumentFile?, driveOffset: Int, ripStates: Map<Int, TrackRipState>) {
        val dir = albumDir ?: return
        try {
            val sb = java.lang.StringBuilder()
            sb.append("BitPerfect Rip Log v").append(BuildConfig.VERSION_NAME).append("\n")
            sb.append("Generated: ").append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now())).append("\n\n")

            sb.append("Album:  ").append(metadata.albumTitle).append("\n")
            sb.append("Artist: ").append(metadata.artistName).append("\n\n")

            sb.append("Drive:  ").append(driveVendor).append(" ").append(driveProduct).append("\n")
            sb.append("Offset: ").append(driveOffset).append(" samples\n\n")

            val arId = computeAccurateRipDiscId(toc)
            sb.append("Disc IDs\n")
            sb.append("  AccurateRip id1:   ").append(String.format("%08x", arId.id1 and 0xFFFFFFFFL)).append("\n")
            sb.append("  AccurateRip id2:   ").append(String.format("%08x", arId.id2 and 0xFFFFFFFFL)).append("\n")
            sb.append("  FreeDB id:         ").append(String.format("%08x", arId.id3)).append("\n")
            sb.append("  MusicBrainz:       ").append(computeMusicBrainzDiscId(toc)).append("\n\n")

            val states = ripStates.values.sortedBy { it.trackNumber }
            val firstAccurateRipUrl: String? = states.firstNotNullOfOrNull { it.accurateRipUrl }
                ?: AccurateRipService().getAccurateRipUrl(toc)

            if (firstAccurateRipUrl != null) {
                sb.append("AccurateRip URL:\n")
                sb.append("  ").append(firstAccurateRipUrl).append("\n\n")
            }

            sb.append("Tracks\n")

            for (state in states) {
                sb.append("  ---------------------------------------------------------------\n")

                val trackTitle = metadata.trackTitles.getOrNull(state.trackNumber - 1) ?: "Track ${state.trackNumber}"
                sb.append(String.format("  %02d  %s\n", state.trackNumber, trackTitle))
                sb.append("      Status:    ").append(state.status.name).append("\n")

                sb.append("      LBA:       ").append(state.startLba).append(" → ").append(state.endLba)
                  .append("  (").append(state.totalSectors).append(" sectors expected, ")
                  .append(state.sectorsRead).append(" read)\n")

                if (state.totalSectors != state.sectorsRead) {
                    sb.append("  *** TRUNCATED ***\n")
                }

                val formattedDuration = String.format(java.util.Locale.US, "%.2fs", state.durationSeconds)
                sb.append("      Duration:  ").append(formattedDuration).append(" (ripped)\n")

                if (state.status == RipStatus.ERROR) {
                    sb.append("      Error:     ").append(state.errorMessage ?: "Unknown error").append("\n")
                } else if (state.computedChecksum != null) {
                    val computedStr = String.format("0x%08X", state.computedChecksum and 0xFFFFFFFFL)
                    when (state.status) {
                        RipStatus.SUCCESS -> {
                            sb.append("      Checksum:  ").append(computedStr).append("  ✓ matched\n")
                        }
                        RipStatus.WARNING -> {
                            sb.append("      Checksum:  ").append(computedStr).append("  ← computed\n")
                            val expectedStr = state.expectedChecksums.joinToString(", ") { String.format("0x%08X", it and 0xFFFFFFFFL) }
                            sb.append("      Expected:  ").append(expectedStr).append("\n")
                        }
                        RipStatus.UNVERIFIED -> {
                            sb.append("      Checksum:  ").append(computedStr).append("  (not in AccurateRip database)\n")
                        }
                        else -> {
                            sb.append("      Checksum:  ").append(computedStr).append("\n")
                        }
                    }
                }
            }
            if (states.isNotEmpty()) {
                sb.append("  ---------------------------------------------------------------\n")
            }

            dir.findFile("rip.txt")?.delete()
            val destFile = dir.createFile("text/plain", "rip.txt")
            if (destFile != null) {
                context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            AppLogger.w("RipManager", "Failed to write rip.txt: ${e.message}")
        }
    }
}
