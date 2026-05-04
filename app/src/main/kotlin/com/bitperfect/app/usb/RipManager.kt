package com.bitperfect.app.usb

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.services.AccurateRipVerifier
import com.bitperfect.core.services.AccurateRipTrackMetadata
import com.bitperfect.core.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

enum class RipStatus {
    IDLE, RIPPING, VERIFYING, SUCCESS, WARNING, ERROR, CANCELLED
}

data class TrackRipState(
    val trackNumber: Int,
    val progress: Float = 0f,
    val status: RipStatus = RipStatus.IDLE
)

class RipManager(
    private val context: Context,
    private val outputFolderUriString: String,
    private val toc: DiscToc,
    private val metadata: DiscMetadata,
    private val expectedChecksums: Map<Int, List<AccurateRipTrackMetadata>>,
    private val coverArtUrl: String?
) {
    private val _trackStates = MutableStateFlow<Map<Int, TrackRipState>>(
        toc.tracks.associate { it.trackNumber to TrackRipState(it.trackNumber) }
    )
    val trackStates: StateFlow<Map<Int, TrackRipState>> = _trackStates

    private var isCancelled = false
    private val verifier = AccurateRipVerifier()

    suspend fun startRipping() = withContext(Dispatchers.IO) {
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

        // Download cover art once
        var coverArtBytes: ByteArray? = null
        if (!coverArtUrl.isNullOrEmpty()) {
            try {
                coverArtBytes = URL(coverArtUrl).readBytes()
            } catch (e: Exception) {
                AppLogger.w("RipManager", "Failed to download cover art: ${e.message}")
            }
        }

        for (i in 0 until toc.tracks.size) {
            if (isCancelled) break

            val entry = toc.tracks[i]
            val trackNumber = entry.trackNumber
            val trackTitle = metadata.trackTitles.getOrNull(i) ?: "Track $trackNumber"
            val nextLba = if (i + 1 < toc.tracks.size) toc.tracks[i + 1].lba else toc.leadOutLba
            val totalSectors = nextLba - entry.lba

            updateTrackState(trackNumber, RipStatus.RIPPING, 0f)

            for (attempt in 1..3) {
                if (isCancelled) break

                val safeTitle = trackTitle.replace("/", "_")
                val filename = String.format("%02d - %s.flac", trackNumber, safeTitle)

                // Write to local cache first so jaudiotagger can operate on a standard File
                val cacheFile = File(context.cacheDir, filename)
                if (cacheFile.exists()) cacheFile.delete()

                var outputStream: java.io.OutputStream? = null
                var encoder: FlacEncoder? = null
                var checksum = 0L

                try {
                    outputStream = FileOutputStream(cacheFile)
                    encoder = FlacEncoder(outputStream)
                    encoder.start()

                    val chunkSize = 26 // read ~26 sectors at a time
                    var sectorsRead = 0
                    while (sectorsRead < totalSectors && !isCancelled) {
                        val sectorsToRead = minOf(chunkSize, totalSectors - sectorsRead)
                        val pcmData = readCmd.execute(entry.lba + sectorsRead, sectorsToRead)

                        if (pcmData != null) {
                            encoder.encode(pcmData)
                            checksum += verifier.computeChecksum(pcmData)
                        } else {
                            AppLogger.w("RipManager", "Failed to read sector at ${entry.lba + sectorsRead}")
                            val silence = ByteArray(sectorsToRead * 2352)
                            encoder.encode(silence)
                        }

                        sectorsRead += sectorsToRead
                        updateTrackState(trackNumber, RipStatus.RIPPING, sectorsRead.toFloat() / totalSectors)
                    }

                    encoder.stop()
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

                    if (coverArtBytes != null) {
                        val artwork = ArtworkFactory.getNew()
                        artwork.binaryData = coverArtBytes
                        artwork.mimeType = "image/jpeg"
                        artwork.description = ""
                        artwork.pictureType = 3 // Front Cover
                        tag.deleteArtworkField()
                        tag.setField(artwork)
                    }

                    audioFile.commit()
                } catch (e: Exception) {
                    AppLogger.w("RipManager", "Failed to tag file: ${e.message}")
                }

                // Verify checksum
                checksum = checksum and 0xFFFFFFFFL
                val expectedForTrack = expectedChecksums[trackNumber]
                val isAccurate = expectedForTrack?.any { it.checksum == checksum } ?: true

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

                if (isAccurate) {
                    updateTrackState(trackNumber, RipStatus.SUCCESS, 1f)
                    break
                } else {
                    AppLogger.w("RipManager", "Checksum mismatch for track $trackNumber (attempt $attempt).")
                    if (attempt == 3) {
                        updateTrackState(trackNumber, RipStatus.WARNING, 1f)
                    }
                }
            }
        }
    }

    private fun updateTrackState(trackNumber: Int, status: RipStatus, progress: Float) {
        val currentStates = _trackStates.value.toMutableMap()
        currentStates[trackNumber] = TrackRipState(trackNumber, progress, status)
        _trackStates.value = currentStates
    }

    fun cancel() {
        isCancelled = true
    }
}
