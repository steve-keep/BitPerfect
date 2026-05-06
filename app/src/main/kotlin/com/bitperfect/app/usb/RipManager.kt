package com.bitperfect.app.usb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
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
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL

import com.bitperfect.core.utils.computeAccurateRipDiscId
import com.bitperfect.core.utils.computeMusicBrainzDiscId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class RipStatus {
    IDLE, RIPPING, VERIFYING, SUCCESS, UNVERIFIED, WARNING, ERROR, CANCELLED
}

data class TrackRipState(
    val trackNumber: Int,
    val progress: Float = 0f,
    val status: RipStatus = RipStatus.IDLE,
    val accurateRipUrl: String? = null,
    val computedChecksum: Long? = null,
    val expectedChecksums: List<Long> = emptyList()
)

class RipManager(
    private val context: Context,
    private val outputFolderUriString: String,
    private val toc: DiscToc,
    private val metadata: DiscMetadata,
    private val expectedChecksums: Map<Int, List<AccurateRipTrackMetadata>>,
    private val artworkBytes: ByteArray?,
    private val driveVendor: String,
    private val driveProduct: String
) {
    private val _trackStates = MutableStateFlow<Map<Int, TrackRipState>>(
        toc.tracks.associate { it.trackNumber to TrackRipState(it.trackNumber) }
    )
    val trackStates: StateFlow<Map<Int, TrackRipState>> = _trackStates

    private var isCancelled = false
    private val verifier = AccurateRipVerifier()

    suspend fun startRipping() = withContext(Dispatchers.IO) {
        val driveOffset: Int = try {
            DriveOffsetRepository(context).findOffset(driveVendor, driveProduct)?.offset ?: 0
        } catch (e: Exception) {
            AppLogger.w("RipManager", "Could not determine drive offset, defaulting to 0: ${e.message}")
            0
        }
        AppLogger.d("RipManager", "Drive offset: $driveOffset samples")

        val transport = DeviceStateManager.getTransport()
        val inEndpoint = DeviceStateManager.getInEndpoint()
        val outEndpoint = DeviceStateManager.getOutEndpoint()

        if (transport == null || inEndpoint == null || outEndpoint == null) {
            AppLogger.e("RipManager", "USB Endpoints not available")
            return@withContext
        }

        val readCmd = ReadCdCommand(transport, outEndpoint, inEndpoint)

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

        val albumDir = artistDir.findFile(safeAlbum) ?: artistDir.createDirectory(safeAlbum)
        if (albumDir == null) {
            AppLogger.e("RipManager", "Could not create album directory")
            return@withContext
        }

        val accurateRipUrl = AccurateRipService().getAccurateRipUrl(toc)

        var carryBuffer = ByteArray(0)

        for (i in 0 until toc.tracks.size) {
            if (isCancelled) break

            val entry = toc.tracks[i]
            val trackNumber = entry.trackNumber
            val trackTitle = metadata.trackTitles.getOrNull(i) ?: "Track $trackNumber"
            val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
            val totalSectors = nextLba - entry.lba
            val totalSamples = totalSectors.toLong() * 588L

            updateTrackState(trackNumber, RipStatus.RIPPING, 0f)

            if (isCancelled) break

            val safeTitle = trackTitle.replace("/", "_")
            val filename = String.format("%02d - %s.flac", trackNumber, safeTitle)

            // Write to local cache first so jaudiotagger can operate on a standard File
            val cacheFile = File(context.cacheDir, filename)
            if (cacheFile.exists()) cacheFile.delete()

            var outputStream: java.io.OutputStream? = null
            var encoder: FlacEncoder? = null
            var finalChecksum = 0L

            try {
                outputStream = FileOutputStream(cacheFile)
                encoder = FlacEncoder(outputStream)
                encoder.start()

                val checksumAccumulator = ChecksumAccumulator(verifier, totalSamples, driveOffset)

                val chunkSize = 26 // read ~26 sectors at a time
                var sectorsRead = 0
                var isFirstChunk = true
                var nextCarryBuffer = ByteArray(0)

                while (sectorsRead < totalSectors && !isCancelled) {
                    val sectorsToRead = minOf(chunkSize, totalSectors - sectorsRead)
                    val pcmData = readCmd.execute(entry.lba + sectorsRead, sectorsToRead)

                    if (pcmData != null) {
                        encoder.encode(pcmData)

                        var dataForAccumulator = pcmData
                        if (isFirstChunk && carryBuffer.isNotEmpty()) {
                            dataForAccumulator = ByteArray(carryBuffer.size + pcmData.size)
                            System.arraycopy(carryBuffer, 0, dataForAccumulator, 0, carryBuffer.size)
                            System.arraycopy(pcmData, 0, dataForAccumulator, carryBuffer.size, pcmData.size)
                        }

                        if (driveOffset < 0 && (sectorsRead + sectorsToRead == totalSectors)) {
                            val bytesToCarry = Math.abs(driveOffset) * 4
                            if (dataForAccumulator.size >= bytesToCarry) {
                                nextCarryBuffer = ByteArray(bytesToCarry)
                                System.arraycopy(dataForAccumulator, dataForAccumulator.size - bytesToCarry, nextCarryBuffer, 0, bytesToCarry)
                            }
                        }

                        checksumAccumulator.accumulate(dataForAccumulator, sectorsToRead)
                    } else {
                        AppLogger.w("RipManager", "Failed to read sector at ${entry.lba + sectorsRead}")
                        val silence = ByteArray(sectorsToRead * 2352)
                        encoder.encode(silence)
                        checksumAccumulator.accumulate(null, sectorsToRead)
                    }

                    isFirstChunk = false
                    sectorsRead += sectorsToRead
                    updateTrackState(trackNumber, RipStatus.RIPPING, sectorsRead.toFloat() / totalSectors)
                }

                encoder.stop()

                // Patch the FLAC STREAMINFO total_samples directly in the output file
                try {
                    RandomAccessFile(cacheFile, "rw").use { raf ->
                        if (raf.length() >= 26) {
                            raf.seek(18)
                            val currentSamplesWord = raf.readLong()
                            val upper28Bits = currentSamplesWord and -0x1000000000L
                            val newSamplesWord = upper28Bits or (totalSamples and 0xFFFFFFFFFL)
                            raf.seek(18)
                            raf.writeLong(newSamplesWord)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w("RipManager", "Failed to patch FLAC total_samples: ${e.message}")
                }

                carryBuffer = nextCarryBuffer
                finalChecksum = checksumAccumulator.finalise()
            } catch (e: Exception) {
                AppLogger.e("RipManager", "Error ripping track $trackNumber", e)
            } finally {
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }

            updateTrackState(trackNumber, RipStatus.VERIFYING, 1f)

            // Apply tags
            try {
                val audioFile = AudioFileIO.read(cacheFile)
                val tag = audioFile.tagOrCreateAndSetDefault
                tag.setField(FieldKey.ARTIST, metadata.artistName)
                tag.setField(FieldKey.ALBUM, metadata.albumTitle)
                tag.setField(FieldKey.TITLE, trackTitle)
                tag.setField(FieldKey.TRACK, trackNumber.toString())

                if (artworkBytes != null) {
                    val picture = org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture(
                        artworkBytes, 3, "image/jpeg", "", 0, 0, 0, 0 // TODO: detect MIME type if non-JPEG sources are added
                    )
                    tag.deleteArtworkField()
                    (tag as org.jaudiotagger.tag.flac.FlacTag).setField(picture)
                }

                audioFile.commit()
            } catch (e: Exception) {
                AppLogger.w("RipManager", "Failed to tag file: ${e.message}")
            }

            // Verify checksum
            val expectedForTrack = expectedChecksums[trackNumber]

            // Move from cache to SAF destination
            try {
                albumDir.findFile(filename)?.delete()
                val destFile = albumDir.createFile("audio/flac", filename)
                if (destFile != null) {
                    context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                        cacheFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("RipManager", "Failed to move file to SAF destination", e)
            } finally {
                cacheFile.delete()
            }

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

        }

        if (!isCancelled) {
            writeRipLog(albumDir, driveOffset, _trackStates.value)
        }
    }

    private fun updateTrackState(
        trackNumber: Int,
        status: RipStatus,
        progress: Float,
        accurateRipUrl: String? = null,
        computedChecksum: Long? = null,
        expectedChecksums: List<Long> = emptyList()
    ) {
        val currentStates = _trackStates.value.toMutableMap()
        currentStates[trackNumber] = TrackRipState(
            trackNumber, progress, status,
            accurateRipUrl, computedChecksum, expectedChecksums
        )
        _trackStates.value = currentStates
    }

    fun cancel() {
        isCancelled = true
    }


    private fun writeRipLog(albumDir: DocumentFile, driveOffset: Int, ripStates: Map<Int, TrackRipState>) {
        try {
            val sb = java.lang.StringBuilder()
            sb.append("BitPerfect Rip Log\n")
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

            sb.append("Tracks\n")
            sb.append("  #  Title                          Status   Checksum\n")
            sb.append("  ---------------------------------------------------------\n")

            val states = ripStates.values.sortedBy { it.trackNumber }
            for (state in states) {
                val trackTitle = metadata.trackTitles.getOrNull(state.trackNumber - 1) ?: "Track ${state.trackNumber}"

                val paddedTitle = if (trackTitle.length > 30) {
                    trackTitle.take(29) + "…"
                } else {
                    trackTitle.padEnd(30, ' ')
                }

                val statusStr = state.status.name.padEnd(8, ' ')
                val checksumStr = if (state.computedChecksum != null) {
                    String.format("0x%08X", state.computedChecksum and 0xFFFFFFFFL)
                } else {
                    "—       "
                }

                sb.append("  ").append(String.format("%02d", state.trackNumber))
                  .append(" ").append(paddedTitle)
                  .append("  ").append(statusStr)
                  .append("  ").append(checksumStr)

                if (state.status == RipStatus.WARNING) {
                    val expectedStr = state.expectedChecksums.joinToString(", ") { String.format("0x%08X", it and 0xFFFFFFFFL) }
                    sb.append("  [expected: ").append(expectedStr).append("]")
                }
                sb.append("\n")
            }

            albumDir.findFile("rip.txt")?.delete()
            val destFile = albumDir.createFile("text/plain", "rip.txt")
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
