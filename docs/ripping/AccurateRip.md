# AccurateRip Pipeline — Technical Documentation

## Overview

AccurateRip is a community-maintained database of per-track CRC checksums derived from rips submitted by thousands of users worldwide. When BitPerfect rips a CD, it computes the same checksums in real time and compares them against the database. A match proves the rip is bit-perfect relative to every other verified copy of the same pressing — independently of whether the raw drive read was perfect.

The pipeline has five distinct stages:

1. **Disc identification** — compute a disc-specific triple-ID from the TOC
2. **Database fetch** — download the binary `.bin` file from AccurateRip's CDN
3. **Binary parsing** — decode the file into per-pressing, per-track checksum records
4. **Checksum accumulation** — compute V1 and V2 hashes in real time as PCM sectors arrive
5. **Verification** — compare computed hashes against every known pressing and report status

---

## Stage 1 — Disc Identification (`DiscIdUtils.kt`)

The AccurateRip ID is a triple `(id1, id2, id3)` that uniquely identifies a disc. All three components are derived from the TOC's track LBAs (logical block addresses).

### id1 — Sum of LSNs

```
id1 = Σ LSN(track_n)  +  LSN(lead-out)
LSN = LBA - 150        // subtract the 150-frame CD lead-in
```

Every track offset (including the lead-out) is converted to an LSN and summed. This is a simple additive fingerprint of the layout.

### id2 — Weighted sum of LSNs

```
id2 = Σ ( max(LSN(track_n), 1) × trackNumber )  +  LSN(lead-out) × (trackCount + 1)
```

Each track's LSN is multiplied by its 1-based track number. The lead-out is treated as track `N+1`. The `max(..., 1)` guard prevents multiplying by zero for a degenerate first track with LSN 0. This produces a second independent polynomial over the offsets.

### id3 — freedb CDDB ID

```
id3 = (checksum % 255) << 24  |  discLengthSeconds << 8  |  trackCount
```

Where `checksum = Σ digitSum(track_start_seconds)`. This is the classic freedb algorithm, reproduced verbatim. The digit-sum recursion operates on the raw second value of each track's absolute LBA, not its offset within the disc.

### CD-Extra handling

For CD-Extra discs (mixed audio+data with multiple sessions), the audio lead-out differs from the global lead-out. `computeAccurateRipDiscId` uses `toc.audioLeadOutLba` when present, falling back to `toc.leadOutLba`. This matches MusicBrainz's convention and avoids a disc ID mismatch that would produce a CDN miss.

### URL construction (`AccurateRipDiscId.toUrl`)

The CDN URL is deterministically derived from the three IDs:

```
http://www.accuraterip.com/accuraterip/{id1[7]}/{id1[6]}/{id1[5]}/
    dBAR-{trackCount:03d}-{id1:08x}-{id2:08x}-{id3:08x}.bin
```

The three directory levels are the 5th, 6th, and 7th hex digits (0-indexed from the right) of `id1`, sharding the CDN across 4096 directories. All three IDs are formatted as zero-padded 8-character lowercase hex.

---

## Stage 2 — Database Fetch (`AccurateRipService.kt` / `AccurateRipClient.kt`)

`AccurateRipService.getExpectedChecksums(toc)` is called once per disc, before any tracks are ripped. It:

1. Computes the disc ID and derives the URL
2. Issues an HTTP GET via `AccurateRipClient` (a thin Ktor/OkHttp wrapper with a `BitPerfect/1.0` User-Agent)
3. On HTTP 200, reads the full response body as raw bytes and forwards to the parser
4. On any non-200 or network error, returns an empty list — the rip proceeds as UNVERIFIED

The full response body is read into memory in one shot (`readBytes()`). AccurateRip `.bin` files are small: a 15-track disc with 10 known pressings is ~1.5 KB for V2 format.

`checkIsKeyDisc` is a lightweight variant that issues the same request but only checks whether a 200 response arrives, without parsing. It is used by the calibration flow to confirm a disc is in the database before attempting offset convergence.

---

## Worked Example — `dBAR-011-000cb4d1-006e975f-9507580b.bin`

This is a real AccurateRip response for an 11-track disc. The full file is 224 bytes.

### Decoding the filename

The filename itself encodes the disc identity:

```
dBAR - 011        - 000cb4d1 - 006e975f - 9507580b .bin
       trackCount   id1        id2        id3(CDDB)
```

The CDN directory path is derived from the last three hex digits of `id1` (`000cb4d1`), reading right to left: `1/d/4/`. The full URL is:

```
http://www.accuraterip.com/accuraterip/1/d/4/dBAR-011-000cb4d1-006e975f-9507580b.bin
```

### File structure

The file contains **2 pressings × (13-byte header + 11 × 9-byte track records) = 112 bytes each = 224 bytes total**. The dry-run V2 pass consumes all 224 bytes exactly, so the format is **V2**.

### Pressing 1 — bytes 0–111

```
Byte  0       : 0x0b              → trackCount = 11
Bytes 1–4     : d1 b4 0c 00 LE   → discId1 = 0x000cb4d1
Bytes 5–8     : 5f 97 6e 00 LE   → discId2 = 0x006e975f
Bytes 9–12    : 0b 58 07 95 LE   → cddb    = 0x9507580b  (consumed, discarded)
```

Track records follow at bytes 13–111, 9 bytes each:

```
Track 01  04 53d11a01 30a3889f  → conf=4  crcV2=0x011ad153  crcV1=0x9f88a330
Track 02  04 60a97926 eb580283  → conf=4  crcV2=0x2679a960  crcV1=0x830258eb
Track 03  04 e024f368 cafa5ab5  → conf=4  crcV2=0x68f324e0  crcV1=0xb55afaca
Track 04  04 9322388a 08db4da7  → conf=4  crcV2=0x8a382293  crcV1=0xa74ddb08
Track 05  04 8d13d2df 0a0c58c6  → conf=4  crcV2=0xdfd2138d  crcV1=0xc6580c0a
Track 06  04 54fbf999 cfd02198  → conf=4  crcV2=0x99f9fb54  crcV1=0x9821d0cf
Track 07  04 db28864b 05723c11  → conf=4  crcV2=0x4b8628db  crcV1=0x113c7205
Track 08  04 3a03f6a7 b12342bf  → conf=4  crcV2=0xa7f6033a  crcV1=0xbf4223b1
Track 09  04 4e62d083 3ee99eb2  → conf=4  crcV2=0x83d0624e  crcV1=0xb29ee93e
Track 10  04 a06e18b6 5b7b356e  → conf=4  crcV2=0xb6186ea0  crcV1=0x6e357b5b
Track 11  04 3ffc154c f4d6bf8a  → conf=4  crcV2=0x4c15fc3f  crcV1=0x8abfd6f4
```

Within each 9-byte track record, remember the field order trap: the first 4-byte chunk is `crcV2`, the second is `crcV1`. For Track 01, the raw bytes after the confidence byte are `53 d1 1a 01` (= `0x011ad153` LE) and `30 a3 88 9f` (= `0x9f88a330` LE). The parser maps these as `firstCrc → crcV2`, `secondCrc → crcV1`.

Confidence = 4 on every track means 4 independent users submitted identical checksums for this pressing.

### Pressing 2 — bytes 112–223 (V2-only pressing)

The header is identical to pressing 1 (same disc, different pressing record):

```
Byte  112     : 0x0b              → trackCount = 11
Bytes 113–116 : d1 b4 0c 00 LE   → discId1 = 0x000cb4d1
Bytes 117–120 : 5f 97 6e 00 LE   → discId2 = 0x006e975f
Bytes 121–124 : 0b 58 07 95 LE   → cddb    = 0x9507580b
```

Track records:

```
Track 01  02 10058811 00000000  → conf=2  crcV2=0x11880510  crcV1=0x00000000
Track 02  02 882fa2b8 00000000  → conf=2  crcV2=0xb8a22f88  crcV1=0x00000000
...
Track 11  02 43ab2975 00000000  → conf=2  crcV2=0x7529ab43  crcV1=0x00000000
```

All `crcV1` fields are `0x00000000`. This is a **V2-only pressing**: the two submitters used a ripper that computed V2 hashes but did not back-calculate V1. AccurateRip stores zero as a sentinel for an absent V1 value within a V2 record.

This is safe in the BitPerfect verifier because the elimination logic checks `dbTrack.crcV2 != null` to decide which hash to use. Since `crcV2` is non-null (and non-zero) for every track in pressing 2, the V2 branch is always taken and the zero V1 fields are never compared against anything. If `crcV2` were null, falling through to compare a computed checksum against `0x00000000` would be a false match risk — but that situation can't arise for a V2 record.

Confidence = 2 here, versus 4 for pressing 1, indicating fewer submitters. If a rip matches pressing 2 but not pressing 1, the UI will report confidence 2.

---

## Worked Example 2 — `dBAR-012-00151845-00c504b0-a70de90c.bin`

This file is a much richer real-world response: **16 pressings** of a 12-track disc, totalling **1936 bytes**. It illustrates several patterns not visible in the first example.

### Filename and byte budget

```
dBAR - 012        - 00151845 - 00c504b0 - a70de90c .bin
       trackCount   id1        id2        id3(CDDB)
```

CDN path derived from `id1 = 00151845` → digits at positions 7/6/5 (right to left): `5/4/8/`.

Per pressing: 13 header + 12 × 9 = **121 bytes**. 16 × 121 = **1936 bytes** — exact file size confirmed.

### Confidence gradient across pressings

Unlike the first file where all pressings had uniform confidence, this file shows a clear gradient reflecting how many users have submitted each pressing:

| Pressings | Confidence range | Character |
|---|---|---|
| 1 | 18–19 | High-confidence primary pressing (most common version) |
| 2 | 17 | Second most common pressing |
| 3 | 10–12 | Another well-verified variant |
| 4–5 | 8–10 | Less common pressings, still reliable |
| 6–8 | 4–7 | Minority pressings |
| 9–15 | 2–4, V2-only | Modern V2-only submissions; no V1 back-calculation |
| 16 | 0–2, partial | Partially submitted pressing (see below) |

Track 12 consistently has slightly lower confidence than the rest across pressings 1–8 — a real-world pattern where the final track of a disc attracts fewer complete submissions, possibly due to users stopping after audible verification of early tracks.

### Per-pressing confidence variation (Pressing 3)

Within Pressing 3, individual tracks have different confidence values:

```
Track 01: conf=12   Track 06: conf=11   Track 11: conf=12
Track 02: conf=12   Track 07: conf=11   Track 12: conf=10  ← lowest
Track 03: conf=11   Track 08: conf=11
Track 04: conf=12   Track 09: conf=11
Track 05: conf=12   Track 10: conf=12
```

This is valid — confidence is stored per track, not per pressing. Different users may have submitted partial datasets (e.g. only some tracks), so individual tracks within a pressing can have different submission counts.

### V2-only pressings (9–15)

Pressings 9–15 all have `crcV1 = 0x00000000` across every track, identical to the pattern seen in pressing 2 of the first example. This confirms it is a systematic characteristic of submissions from V2-capable rippers that don't back-compute V1, not a quirk of any single file.

### Pressing 16 — partial submission (bytes 1815–1935)

This pressing is structurally valid but contains tracks with all-zero confidence **and** all-zero CRCs:

```
Track 01: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 02: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 03: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 04: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 05: conf=2  crcV2=0xa9cf82b5  crcV1=0x00000000
Track 06: conf=2  crcV2=0xd760597e  crcV1=0x00000000
Track 07: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 08: conf=2  crcV2=0xff35c7b2  crcV1=0x00000000
Track 09: conf=2  crcV2=0x340f4faf  crcV1=0x00000000
Track 10: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
Track 11: conf=2  crcV2=0x696cf005  crcV1=0x00000000
Track 12: conf=0  crcV2=0x00000000  crcV1=0x00000000  ← no data
```

Six of the twelve tracks have zero confidence and zero CRCs — AccurateRip's sentinel for "no submissions received for this track in this pressing". The other six have real V2 hashes with confidence 2.

This has a direct consequence in BitPerfect's candidate elimination. When the verifier encounters a track where `pressing.tracks[trackNumber]` returns a record with `crcV2 == 0x00000000`, it will compare the computed checksum against zero. A real rip will virtually never produce a zero checksum, so pressing 16 will be correctly eliminated on any all-zero track. However, the elimination logic uses `dbTrack.crcV2 != null` to decide the V2 branch — and `crcV2` here is non-null (it's zero, not absent) — meaning BitPerfect will attempt a V2 comparison of `computed != 0x00000000` and drop this pressing cleanly. The pressing would only survive if the computed V2 checksum were coincidentally zero, which is astronomically unlikely.

### Comparison summary

| Property | Example 1 (`dBAR-011-...`) | Example 2 (`dBAR-012-...`) |
|---|---|---|
| File size | 224 bytes | 1936 bytes |
| Format | V2 | V2 |
| Pressings | 2 | 16 |
| Tracks | 11 | 12 |
| Bytes per pressing | 112 | 121 |
| Max confidence | 4 | 19 |
| V2-only pressings | 1 (pressing 2) | 8 (pressings 9–16) |
| Partial pressing | None | 1 (pressing 16, 6 tracks missing) |
| Per-track confidence variation | No | Yes |

---

## Stage 3 — Binary Parsing (`AccurateRipVerifier.parseAccurateRipResponse`)

The `.bin` file is a concatenation of **disc pressing records**, one per known pressing. Each record has a 13-byte header followed by a variable-length track array. All values are **little-endian**.

### Binary layout

```
Header (13 bytes):
  [0]     trackCount  : UInt8
  [1..4]  discId1     : UInt32 LE
  [5..8]  discId2     : UInt32 LE
  [9..12] cddbId      : UInt32 LE  — consumed and discarded (see note below)

Per-track entry, repeated trackCount times:
  V1 format (5 bytes):
    [0]    confidence  : UInt8
    [1..4] crcV1       : UInt32 LE

  V2 format (9 bytes):
    [0]    confidence  : UInt8
    [1..4] crcV2       : UInt32 LE  ← stored FIRST in binary
    [5..8] crcV1       : UInt32 LE  ← stored SECOND in binary
```

The CDDB field must be strictly consumed even though it is not stored. Failing to read it shifts the buffer position by 4 bytes, causing the confidence byte of every subsequent track to be read from CDDB data. This was a real bug caught by the `CDDB bytes do not appear in parsed CRC` regression test, which reproduces the exact White Blood Cells disc that exposed it.

### V1 vs V2 auto-detection

AccurateRip responses carry no explicit format header. The parser performs a **dry-run** before parsing: it advances through the entire `ByteBuffer` treating every track record as 9 bytes (V2 size). If the pointer aligns exactly with the end of the file (`buf.remaining() == 0`), the file is parsed as V2. Otherwise it falls back to 5-byte V1 records.

This is reliable in practice because a given `.bin` file is internally consistent — all pressing records within it use the same format.

The field order reversal in V2 is a common trap: the binary layout puts `crcV2` first and `crcV1` second, so the parsing logic explicitly maps `firstCrc → crcV2` and `secondCrc → crcV1` when in V2 mode.

### Result model

```kotlin
data class AccurateRipTrackMetadata(
    val crcV1: Long,       // 32-bit checksum stored as unsigned Long
    val crcV2: Long?,      // null for V1-only database entries
    val confidence: Int    // number of independent submitters who reported this CRC
)

data class AccurateRipDiscPressing(
    val discId1: Long,
    val discId2: Long,
    val tracks: Map<Int, AccurateRipTrackMetadata>  // 1-based track number → metadata
)
```

Parsing returns a `List<AccurateRipDiscPressing>` — every pressing the database holds for this disc.

---

## Stage 4 — Checksum Accumulation (`ChecksumAccumulator.kt`)

A `ChecksumAccumulator` is created per track at the start of each rip. As decoded PCM data arrives in chunks from the ripping loop, `accumulate(pcmData)` is called repeatedly. It computes both V1 and V2 checksums in a single pass over the data.

> Note: `AccurateRipVerifier.computeChecksumChunk()` is an older single-pass helper that remains in the codebase but is marked `@Deprecated`. The canonical hot-path is `ChecksumAccumulator.accumulate()`.

### The checksum algorithm

Both algorithms iterate over every 4-byte stereo sample frame. The four bytes are assembled into a single unsigned 32-bit value (`sampleValue`) in little-endian order.

**V1:**
```
checksum += sampleValue × samplePosition
checksum &= 0xFFFFFFFF
```

**V2:**
```
product = sampleValue × samplePosition   // full 64-bit product retained
lo = product & 0xFFFFFFFF
hi = (product >> 32) & 0xFFFFFFFF
checksum += lo + hi
checksum &= 0xFFFFFFFF
```

V2's fold of the 64-bit product (`lo + hi`) is what differentiates it from V1. It was introduced to detect mastering errors — specifically from certain pressing plants that produced consistent off-by-one artefacts at 32-bit word boundaries — that V1 silently passed.

`samplePosition` is 1-based and starts at 1 for the first sample of the track. It is threaded through successive `accumulate()` calls via the `samplePosition` field, making chunk boundaries fully transparent to the algorithm.

### Exclusion windows

The first and last 2940 samples (5 sectors = 5 × 588 samples) **of the disc** — not of each track, but of the first track and last track respectively — are excluded from the checksum. This compensates for the fact that different drives apply different amounts of pregap/postgap silence at disc boundaries, which would otherwise produce divergent checksums across identical rips.

```kotlin
val skipStart = if (isFirstTrack) 2940L else 0L
val skipEnd   = if (isLastTrack)  2940L else 0L

if (currentSamplePos >= skipStart && currentSamplePos <= totalSamples - skipEnd) {
    // accumulate
}
```

The boundaries are **inclusive**: sample index 2940 is the first sample included for the first track, and `totalSamples - 2940` is the last sample included for the last track. Middle tracks have `skipStart = 0` and `skipEnd = 0`, so every sample contributes.

### Drive offset compensation

CD drives have a read offset: the physical head starts a few samples early or late relative to the nominal track boundary. BitPerfect stores this per-drive in settings, converged via the calibration flow described below. Before checksum accumulation begins, the drive offset is decomposed in `RipManager`:

- `tocOffset` — whole sectors to shift (`offset ÷ 588`, rounded toward zero)
- `sampleOffset` — remaining fractional samples (`offset mod 588`)
- `skipBytes` — bytes to trim from the first sector's PCM (`sampleOffset × 4`)

This ensures the PCM fed into `ChecksumAccumulator` is correctly aligned to the track boundary as AccurateRip defines it, independently of where the drive physically started reading.

### Finalisation

Once all chunks for a track are processed, `finalise()` returns a `Pair<Long, Long>` of `(crcV1, crcV2)`, both masked to 32 bits via `and 0xFFFFFFFFL`. These are the values submitted to verification.

---

## Stage 5 — Verification (`RipManager.kt`)

Once all sectors for a track are ripped and the FLAC is written, the finalised checksums are compared against the database using a **progressive candidate elimination** algorithm.

### The candidate pool

Before the track loop begins, `activePressingCandidates` is initialised as a `MutableSet` containing every `AccurateRipDiscPressing` returned from the database. This pool is maintained at the **disc level** across all tracks — not reset per track — which is essential. Without this, a rip could match Track 1 of Pressing A and Track 2 of Pressing B and incorrectly report a verified result.

### Elimination process

After each track completes, the pool is pruned:

```kotlin
activePressingCandidates.retainAll { pressing ->
    val dbTrack = pressing.tracks[trackNumber] ?: return@retainAll false
    if (dbTrack.crcV2 != null) {
        dbTrack.crcV2 == finalChecksumV2
    } else {
        dbTrack.crcV1 == finalChecksumV1
    }
}
```

A pressing is kept only if its checksum matches for this track. V2 takes precedence when the database entry has one; otherwise V1 is used. A pressing is permanently eliminated the moment any single track fails to match.

### Status classification

| Condition | Status |
|---|---|
| `activePressingCandidates` non-empty | `SUCCESS` — at least one pressing matches all tracks so far |
| Pool reached zero, but database had entries | `WARNING` — rip is likely bad or drive offset is wrong |
| Track absent from database entirely | `UNVERIFIED` — cannot confirm either way |

The matched version (1 or 2) and the maximum confidence score across surviving pressings are also recorded. Confidence represents the number of independent submitters — a value of 3 or higher is generally considered reliable.

### Expected checksums always from the full database

When populating the "expected checksums" fields for UI display and logging, BitPerfect always reads from the original unfiltered `expectedChecksums` list, never from `activePressingCandidates`. This ensures the UI accurately shows what the database actually holds even when verification fails and the candidate pool is empty.

### Logging

Results are written to a per-album `accuraterip.jsonl` file via `writeAccurateRipJsonl`. Each line is a JSON object containing `isVerified`, `checksumMatched`, `matchedVersion`, `confidence`, `inDatabase`, `checksumV1`/`checksumV2` as `0xHEX` strings, and the full lists of expected V1 and V2 checksums from the database. This forensic log is independent of the FLAC file and survives a re-import.

---

## Drive Offset Calibration (`OffsetCalibrationViewModel.kt`)

AccurateRip is also the mechanism used to calibrate the per-drive sample offset. The flow:

1. The user inserts a **key disc** — one that exists in the AccurateRip database with high confidence
2. `checkIsKeyDisc` confirms it before attempting calibration
3. The disc is ripped across a sweep of candidate offsets (three independent discs for a robust result)
4. For each candidate offset, the computed checksums are matched against AccurateRip
5. Each disc produces a **set of offsets** that yield a verified match
6. The three sets are intersected — the offset satisfying all three discs is the true drive offset
7. If the intersection contains exactly one value, calibration succeeds; if empty, the discs disagree (unusual drive behaviour); if multiple values survive, the one closest to zero is selected

For the ASUS SDRW-08D2S-U used in development this process converges on **+6 samples**.

---

## Key Bugs Fixed (Historical)

| Bug | Symptom | Fix |
|---|---|---|
| Missing CDDB field consumption | Buffer offset wrong for all tracks — confidence byte read from CDDB data | Added `buffer.getInt()` to consume the 4-byte CDDB before reading track records |
| V2 field order misread | `crcV1` and `crcV2` swapped in parsed output | Corrected: binary stores `crcV2` first, `crcV1` second in V2 format |
| `audioLeadOutLba` vs `leadOutLba` for CD-Extra | Wrong disc ID, CDN miss for all CD-Extra discs | Use `audioLeadOutLba` (session-1 lead-out) when present |
| Inter-session gap constant | `11250` used instead of `11400` frames | Corrected constant |
| 64-bit overflow in V1 accumulator | `sampleValue × samplePosition` overflowed without masking | Mask result to `0xFFFFFFFFL` after each sample |
| dBAR header parsed as 9 bytes | CDDB field not consumed, producing phantom expected values | Full 13-byte header now consumed |
