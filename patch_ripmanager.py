import re

with open("app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt", "r") as f:
    content = f.read()

# Replace driveOffset logging, and add tocOffset, sampleOffset, skipBytes, overreadBuffer
old_setup = """        val accurateRipUrl = AccurateRipService().getAccurateRipUrl(toc)

        var carryBuffer = ByteArray(0)

        for (i in 0 until toc.tracks.size) {"""

new_setup = """        val accurateRipUrl = AccurateRipService().getAccurateRipUrl(toc)

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

        for (i in 0 until toc.tracks.size) {"""

content = content.replace(old_setup, new_setup)

# We will remove carryBuffer definition later, but actually we already did above.

# Now let's change the ChecksumAccumulator initialization and the read loop
old_loop_setup = """                val isFirstTrack = (i == 0)
                val isLastTrack  = (i == toc.tracks.size - 1)

                val checksumAccumulator = ChecksumAccumulator(
                    verifier      = verifier,
                    totalSamples  = totalSamples,
                    driveOffset   = driveOffset,
                    isFirstTrack  = isFirstTrack,
                    isLastTrack   = isLastTrack
                )

                val chunkSize = 8 // read ~8 sectors at a time
                var chunkCarry = carryBuffer
                val offsetBytes = Math.abs(driveOffset) * 4

                while (sectorsRead < totalSectors && !isCancelled) {"""

new_loop_setup = """                val isFirstTrack = (i == 0)
                val isLastTrack  = (i == toc.tracks.size - 1)

                val checksumAccumulator = ChecksumAccumulator(
                    verifier      = verifier,
                    totalSamples  = totalSamples,
                    isFirstTrack  = isFirstTrack,
                    isLastTrack   = isLastTrack
                )

                val chunkSize = 8 // read ~8 sectors at a time
                val lbaStart = entry.lba + tocOffset + if (overreadBuffer != null) 1 else 0

                if (overreadBuffer != null) {
                    encoder.encode(overreadBuffer!!)
                    checksumAccumulator.accumulate(overreadBuffer!!)
                }

                var isFirstSector = true

                while (sectorsRead < totalSectors && !isCancelled) {"""

content = content.replace(old_loop_setup, new_loop_setup)

old_read_loop = """                    for (attempt in 1..MAX_READ_RETRIES) {
                        pcmData = readCmd.execute(entry.lba + sectorsRead - toc.pregapOffset, sectorsToRead)
                        if (pcmData != null) {
                            break
                        }
                        if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                            break
                        }
                    }

                    if (pcmData != null) {
                        val sectorsActuallyRead = pcmData.size / 2352
                        if (sectorsActuallyRead < sectorsToRead) {
                            AppLogger.w("RipManager", "Short read at LBA ${entry.lba + sectorsRead}: " +
                                "got $sectorsActuallyRead of $sectorsToRead sectors")
                        }

                        encoder.encode(pcmData)
                        sectorsRead += sectorsActuallyRead

                        val chunkForChecksum: ByteArray = when {
                            driveOffset > 0 -> {
                                val combined = chunkCarry + pcmData
                                chunkCarry = combined.copyOfRange(combined.size - offsetBytes, combined.size)
                                combined.copyOfRange(0, combined.size - offsetBytes)
                            }
                            driveOffset < 0 -> {
                                val combined = chunkCarry + pcmData
                                chunkCarry = combined.copyOfRange(0, offsetBytes)
                                combined.copyOfRange(offsetBytes, combined.size)
                            }
                            else -> pcmData
                        }

                        if (chunkForChecksum.isNotEmpty()) {
                            checksumAccumulator.accumulate(chunkForChecksum)
                        }
                    } else {
                        if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                            isCancelled = true
                            throw java.io.IOException("Disc removed or drive not ready during rip")
                        }
                        throw java.io.IOException("Failed to read sector ${entry.lba + sectorsRead} after $MAX_READ_RETRIES attempts")
                    }"""

new_read_loop = """                    for (attempt in 1..MAX_READ_RETRIES) {
                        pcmData = readCmd.execute(lbaStart + sectorsRead - toc.pregapOffset, sectorsToRead)
                        if (pcmData != null) {
                            break
                        }
                        if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                            break
                        }
                    }

                    if (pcmData != null) {
                        val sectorsActuallyRead = pcmData.size / 2352
                        if (sectorsActuallyRead < sectorsToRead) {
                            AppLogger.w("RipManager", "Short read at LBA ${lbaStart + sectorsRead}: " +
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
                        throw java.io.IOException("Failed to read sector ${lbaStart + sectorsRead} after $MAX_READ_RETRIES attempts")
                    }"""

content = content.replace(old_read_loop, new_read_loop)

old_post_loop = """                encoder.stop()

                if (driveOffset != 0 && chunkCarry.isNotEmpty()) {
                    if (driveOffset < 0 || isLastTrack) {
                        checksumAccumulator.accumulate(chunkCarry)
                    }
                }

                updateTrackState("""

new_post_loop = """                if (sampleOffset > 0) {
                    if (!isLastTrack) {
                        var overreadPcm: ByteArray? = null
                        for (attempt in 1..MAX_READ_RETRIES) {
                            overreadPcm = readCmd.execute(lbaStart + totalSectors - toc.pregapOffset, 1)
                            if (overreadPcm != null) break
                            if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) break
                        }
                        if (overreadPcm == null) {
                            if (DeviceStateManager.driveStatus.value !is DriveStatus.DiscReady) {
                                isCancelled = true
                                throw java.io.IOException("Disc removed or drive not ready during rip")
                            }
                            throw java.io.IOException("Failed to read overshoot sector ${lbaStart + totalSectors} after $MAX_READ_RETRIES attempts")
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
                }

                encoder.stop()

                updateTrackState("""

content = content.replace(old_post_loop, new_post_loop)

old_carry_flush = """                )

                carryBuffer = if (driveOffset > 0) chunkCarry else ByteArray(0)
                finalChecksum = checksumAccumulator.finalise()"""

new_carry_flush = """                )

                finalChecksum = checksumAccumulator.finalise()"""

content = content.replace(old_carry_flush, new_carry_flush)

with open("app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt", "w") as f:
    f.write(content)
