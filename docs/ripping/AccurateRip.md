# AccurateRip Documentation

This document outlines how BitPerfect interfaces with the AccurateRip database to verify CD rips, covering binary response parsing, checksum computation, and the candidate elimination pattern used during extraction.

## Parsing AccurateRip Responses

When BitPerfect queries the AccurateRip HTTP API, the response is a binary `.bin` file containing concatenated disc pressing records. BitPerfect parses this data in `AccurateRipVerifier`.

### Dry-Run V2 Detection

AccurateRip responses do not have an explicit header indicating the format version. Instead, there are two potential formats:
- **V1 Format:** 5 bytes per track (1 byte confidence + 4 bytes CRC)
- **V2 Format:** 9 bytes per track (1 byte confidence + 4 bytes V2 CRC + 4 bytes V1 CRC)

Because responses often contain multiple concatenated pressing records, standard buffer-remaining checks (e.g. `buffer.remaining() == trackCount * 9`) are unreliable. BitPerfect determines the format dynamically by running a "dry-run" pass over the entire `ByteBuffer`. It assumes V2 (9 bytes per track) and advances pointers through the entire payload. If the pointer perfectly aligns with the end of the file (`buf.remaining() == 0`), the response is parsed as V2. Otherwise, it falls back to V1.

### Binary Layout & Extraction

Once the format is known, the buffer is processed sequentially. The byte order is strictly **Little Endian**.

**Disc Entry Header (13 bytes):**
1. `Track Count` (1 byte, unsigned)
2. `Disc ID 1` (4 bytes, unsigned integer)
3. `Disc ID 2` (4 bytes, unsigned integer)
4. `CDDB ID` / `Disc ID 3` (4 bytes, unsigned integer) - *This field must be strictly consumed and skipped. A known edge case involves failing to consume this field, causing it to bleed into track confidence and CRC values.*

**Track Records (Iterated `Track Count` times):**
*   **Confidence** (1 byte, unsigned): Indicates how many other users have submitted this identical rip.
*   **Hash 1** (4 bytes, unsigned integer)
*   **Hash 2** (4 bytes, unsigned integer) - *Only present if V2 format.*

**Extracting Checksums:**
AccurateRip places the V2 hash before the V1 hash in a V2 response. Therefore, the parsing logic maps the read bytes as follows:
*   If **V2**: `crcV2` = Hash 1, `crcV1` = Hash 2
*   If **V1**: `crcV1` = Hash 1, `crcV2` = null

## Checksum Computation

The core algorithm for both V1 and V2 AccurateRip verification revolves around a split-multiplication process, accumulating checksum values across sequential audio chunks. BitPerfect implements this in `AccurateRipVerifier.computeChecksumChunk()`.

### Sample Exclusion Boundaries

CD tracks extracted from the drive often contain leading/trailing anomalies due to drive offsets. AccurateRip handles this by strictly ignoring 5 sectors (2940 samples) at the boundary edges of the disc:
*   **First Track:** Skips the first 2940 samples (`skipStart = 2940`).
*   **Last Track:** Skips the last 2940 samples (`skipEnd = 2940`).
*   **Middle Tracks:** No samples are skipped.

These boundaries are inclusive—sample index 2940 is the *first* sample included in the hash, and `totalSamples - 2940` is the *last* sample included.

### Split-Multiplication Accumulation

Audio data is fed in raw, 16-bit stereo PCM chunks (Little Endian, 4 bytes per sample frame). For every included sample, the algorithm calculates a partial checksum contribution:
1.  **Sample Construction:** The four bytes are combined into a single unsigned 32-bit sample value.
2.  **Accumulation:** The sample value is multiplied by its 1-based index (`currentSamplePos`) within the track.
3.  **Masking:** The product is added to the running `partialChecksum`, and the result is immediately masked to 32 bits (`and 0xFFFFFFFFL`).

Because this process handles data chunk-by-chunk, the accumulator object (`ChunkChecksumResult`) must pass the current partial checksum and the `nextSamplePosition` back to the rip loop so the next chunk continues exactly where the previous left off.

### Finalisation

Once all chunks for a track are processed, the accumulated checksum is passed through `finaliseChecksum()`, which ensures the final long value is strictly clamped to a 32-bit unsigned mask.

## Verification & Candidate Elimination Pattern

AccurateRip responses provide checksums for the entire disc, often containing multiple "pressings" (alternative releases of the same CD with slight track variations or offset shifts). BitPerfect manages these variations using a **Candidate Elimination Pattern**.

### The Candidate Pool

Before the track extraction loop begins, `RipManager` loads all parsed `AccurateRipDiscPressing` objects into a mutable pool (`activePressingCandidates`).

This pool represents all known possible combinations of track hashes for the disc. It's crucial that this list is maintained at the *disc* level, not the individual track level. This prevents cross-contamination, ensuring that a CD matching Track 1 of Pressing A doesn't incorrectly validate Track 2 against Pressing B.

### The Elimination Process

As each track finishes extraction, the calculated `crcV1` and `crcV2` values are evaluated against the surviving candidates:
1.  **Iterate Candidates:** We examine each `AccurateRipDiscPressing` remaining in the active pool.
2.  **Evaluate Hashes:**
    *   If the candidate pressing has a V2 hash for this track, the calculated V2 hash is compared against it.
    *   If the candidate lacks a V2 hash, the calculated V1 hash is compared against the candidate's V1 hash.
3.  **Eliminate:** If the calculated hash does not match the candidate's expected hash, that candidate is permanently removed from the active pool.

If at least one candidate survives, the track is considered a match. If multiple candidates survive, the UI reports the highest matching confidence score (`maxOrNull()`) across them.

### Reporting & Status Fallbacks

When the extraction completes or fails validation, a strict reporting fallback applies:
*   **SUCCESS:** The calculated hash matched at least one candidate, and the candidate remains in the pool.
*   **WARNING:** The calculated hash did not match any surviving candidate (the candidate pool reached zero), *but* expected checksums for this track existed in the original response.
*   **UNVERIFIED:** The track is completely absent from the AccurateRip database (no expected checksums ever existed).

To ensure the UI accurately reflects the database state even when validation fails, BitPerfect always populates the "expected hashes" fields from the full, original database (`expectedChecksums`) rather than the filtered `activePressingCandidates` pool.
