# Bit-Perfect CD Ripping: Technical Specification
### Based on Exact Audio Copy (EAC) Methodology — Android Implementation Guide

---

## Table of Contents

1. [CD Audio Fundamentals](#1-cd-audio-fundamentals)
2. [Why CD Ripping Is Non-Trivial](#2-why-cd-ripping-is-non-trivial)
3. [EAC Core Extraction Technologies](#3-eac-core-extraction-technologies)
   - 3.1 Secure Mode (Multi-Pass with Majority Vote)
   - 3.2 Accurate Stream Detection
   - 3.3 Cache-Busting
   - 3.4 C2 Error Pointer Mode
   - 3.5 Burst Mode (Reference)
4. [Sample Offset Correction](#4-sample-offset-correction)
5. [Track Synchronization Technology](#5-track-synchronization-technology)
6. [Gap Detection Technology](#6-gap-detection-technology)
7. [AccurateRip Verification Protocol](#7-accuraterip-verification-protocol)
   - 7.1 Disc Identification (TOC ID)
   - 7.2 AccurateRip v1 Checksum Algorithm
   - 7.3 AccurateRip v2 Checksum Algorithm
   - 7.4 Database Query & Response Protocol
8. [CUETools DB (CTDB) Secondary Verification](#8-cuetools-db-ctdb-secondary-verification)
9. [Test & Copy Verification (Local CRC)](#9-test--copy-verification-local-crc)
10. [EAC Log File Format](#10-eac-log-file-format)
11. [Android Implementation Architecture](#11-android-implementation-architecture)
    - 11.1 USB Host Mode & Drive Access
    - 11.2 MMC/SCSI Command Interface
    - 11.3 CD-DA Read Commands
    - 11.4 Drive Feature Detection
    - 11.5 Recommended Architecture Stack
12. [Android Secure Ripping Pipeline](#12-android-secure-ripping-pipeline)
13. [Drive Offset Detection on Android](#13-drive-offset-detection-on-android)
14. [Error Handling & Recovery Strategy](#14-error-handling--recovery-strategy)
15. [Output Formats & Metadata](#15-output-formats--metadata)
16. [Performance Considerations](#16-performance-considerations)
17. [Feature Parity Matrix: EAC vs Android App](#17-feature-parity-matrix-eac-vs-android-app)

---

## 1. CD Audio Fundamentals

### Physical Format — Red Book (IEC 60908)

Audio CDs are defined by the Red Book standard (Sony/Philips, 1980). The key parameters:

| Parameter | Value |
|---|---|
| Sampling rate | 44,100 Hz |
| Bit depth | 16-bit signed (two's complement) |
| Channels | 2 (stereo: left + right) |
| Bitrate | 1,411.2 kbit/s raw PCM |
| Sector size (DAE) | 2,352 bytes |
| Sectors per second | 75 |
| Samples per sector | 588 stereo pairs (1,176 individual 16-bit samples) |
| Max tracks | 99 |

### Sector Anatomy

Each CD sector (called a timecode frame) contains 2,352 bytes of audio, derived from 98 channel-data frames:

```
CD Sector = 98 × channel-data frames
Each channel-data frame = 33 bytes:
  - 24 bytes: raw audio data
  -  8 bytes: CIRC error correction parity
  -  1 byte:  subcode / subchannel byte

98 frames × 24 bytes = 2,352 bytes of audio per sector
98 frames × 1 byte   =    98 bytes of subchannel data per sector
```

Each channel-data frame is EFM-encoded before being written to disc:

```
EFM frame on disc = 588 bits:
  - 24 bits sync pattern (unique, not found in data)
  -  3 merge bits
  - 33 bytes × (14 EFM bits + 3 merge bits) = 561 bits
Total = 588 bits per raw channel frame
```

### CIRC Error Correction

The **Cross-Interleaved Reed-Solomon Code (CIRC)** provides two interleaved layers of error correction (C1 and C2). Audio data is interleaved across many frames, meaning a physical scratch destroys small bits of many frames rather than large portions of a few. This allows:

- **C1**: Corrects short burst errors (most minor scratches/dust)
- **C2**: Corrects longer burst errors

If both C1 and C2 fail, the drive has two options:
1. Return incorrect (corrupted) data silently
2. Report the error via the C2 error pointer mechanism (not all drives support this)

Standalone CD players add a third layer: **error concealment/interpolation** — they synthesize missing audio from surrounding samples. CD-ROM drives do **not** do this and instead return raw, potentially corrupted data or zeros.

### Subchannel Data

Each sector contains 98 subchannel bytes, organized into 8 sub-channels (P through W). The most important for ripping:

- **Q subchannel**: Contains timecode (MSF), track number, and index. Used for gap detection, TOC construction, and position seeking.
- **P subchannel**: Simple pause flag (marks gaps between tracks)
- **R–W subchannels**: Used for CD+G (karaoke graphics), rarely relevant for audio ripping

### TOC (Table of Contents)

The TOC is stored in the lead-in area of the disc (inner ~7,500 sectors). It maps each track number to a start LBA (Logical Block Address) using MSF (Minutes:Seconds:Frames) timecodes. The drive exposes this via the `READ TOC` SCSI command. Key fields per track entry:

- Track number (1–99)
- Start position (MSF or LBA)
- Track type (audio vs. data)
- Copy permitted flag
- Pre-emphasis flag

---

## 2. Why CD Ripping Is Non-Trivial

### The Jitter Problem

Most CD-ROM drives suffer from **jitter**: when asked to read sector N, they cannot guarantee returning exactly sector N. The drive's read head may start a few samples early or late:

- Typical jitter: ±1 to ±5 sectors (±588 to ±2,940 samples)
- Extreme cases: ±14,000+ samples (observed on some older drives)

Without correction, consecutive reads of adjacent sectors will have gaps (missing samples) or overlaps, producing audible clicks and pops.

**Accurate Stream** is a drive feature that guarantees positioning: when this is supported, the drive guarantees that every sector read starts exactly where the previous one ended, with no gaps.

### The Caching Problem

Many drives cache audio data in an internal buffer. When the same sector is read twice consecutively, the second read returns the cached copy — which is always identical to the first, even if both reads were wrong. This defeats the "read twice and compare" verification strategy.

### The C2 Reliability Problem

Even on drives that support C2 error pointers, the firmware implementation is frequently buggy:

- Some drives flag errors that don't exist (false positives)
- Some drives fail to flag real errors (false negatives)
- EAC documentation explicitly recommends **not** trusting C2 on most drives

### The Offset Problem

Every CD-ROM drive introduces a fixed, consistent **read offset** — a constant number of samples by which all reads are shifted. For example, a drive with offset +667 samples returns data that starts 667 samples into what was requested. The offset must be known and compensated for to produce an accurate rip that matches what was mastered.

---

## 3. EAC Core Extraction Technologies

### 3.1 Secure Mode — Multi-Pass Majority Vote

EAC's defining technique. For each audio sector, EAC reads it multiple times and compares results to achieve high confidence:

**Algorithm:**

```
For each sector S:
  retries_count = 0
  max_batches = error_recovery_quality  // 1, 3, or 5 (user setting)

  WHILE retries_count < max_batches:
    reads = []
    FOR i = 1 to 16:
      reads.append(ReadSector(S))
    
    IF count_most_common(reads) >= 8:
      RETURN most_common(reads)  // Confident result
    
    retries_count++

  // All batches exhausted — mark as "suspicious position"
  RETURN best_guess(reads)
  LOG suspicious position at MSF time
```

**Key parameters:**

| Setting | Max re-read batches | Worst-case reads per sector |
|---|---|---|
| Low quality | 1 batch | 16 reads |
| Medium quality | 3 batches | 48 reads |
| High quality | 5 batches | 80 reads |

With the maximum setting, a bad sector can be read up to **82 times** (80 re-reads + the initial attempted read + one possible partial batch).

**Track Quality Metric:**

```
Track Quality % = (minimum_reads_required / actual_reads_performed) × 100
```

100% means no re-reads were needed. Less than 100% indicates errors were found and corrected. Only "suspicious positions" (no 8-of-16 match found) indicate truly unrecoverable errors.

### 3.2 Accurate Stream Detection

Before entering secure mode, EAC detects whether the drive supports the Accurate Stream feature:

- **Accurate Stream supported**: Sectors are read sequentially, and each new read starts exactly where the last one ended. No jitter compensation is needed between reads. Verification still occurs for error correction.
- **Accurate Stream not supported**: EAC must read overlapping regions and use correlation to align data — the "overlapping read" method.

**Overlapping Read Method (non-accurate-stream drives):**

```
Read sectors N-2 through N+2 (5 sectors total, overlapping)
Find maximum correlation between the end of one read and the start of the next
Use the overlap alignment to eliminate jitter gaps
```

This is similar to cdparanoia's paranoia mode on Linux.

### 3.3 Cache-Busting

For drives that cache audio data, EAC uses overread techniques to force cache invalidation before re-reading a sector:

**Method:**

```
To re-read sector S without cache contamination:
  1. Read a "decoy" region far away from S (e.g., sector S + 10,000)
  2. The cache now contains sectors near S + 10,000
  3. Read sector S again — now guaranteed from disc, not cache
```

The number of cache-busting seeks required depends on the drive's cache size. EAC detects cache size during the automatic feature detection phase.

This is why secure mode on caching drives runs at roughly 1/3 to 1/4 of the drive's rated maximum speed — most of the time is spent on cache-busting seeks.

### 3.4 C2 Error Pointer Mode

Drives with reliable C2 support can skip the 16-reads-per-sector approach. Instead:

```
For each sector S:
  (data, c2_flags) = ReadSectorWithC2(S)
  
  IF c2_flags == 0:
    RETURN data  // No errors reported — single read sufficient
  ELSE:
    // Errors indicated; fall back to multi-pass for this sector only
    RETURN SecureModeRead(S)
```

This mode operates at close to burst speed (near maximum drive speed) for error-free sectors. It's only available on drives with:
- Accurate Stream support
- Non-caching behaviour
- Correct C2 implementation (rare — most drives are excluded)

### 3.5 Burst Mode (Reference — Not Recommended for Bit-Perfect)

EAC also supports burst mode for comparison purposes:

- No re-reads, no error correction
- Single pass at maximum drive speed
- Only rudimentary "stream break" detection
- Reports suspected position of errors (heuristic only)
- **Must not be used if bit-perfect accuracy is required**

---

## 4. Sample Offset Correction

### The Problem

Every CD-ROM drive has a fixed, drive-model-specific **read sample offset**. When the drive is commanded to read sector N (LBA N), it physically positions at a location slightly before or after LBA N, and returns audio samples from that offset position. Offsets are typically:

- Read offset: ±500 to ±700 samples (±1/90 second)
- Some drives: as high as ±1,000+ samples

Without offset correction:
- The first N samples of a rip are missing from the beginning (for positive offsets)
- N extra samples of the preceding track appear at the start (for negative offsets)
- Two rips of the same CD on different drives produce different WAV files

### Offset Values

Offsets are measured in **samples** (not bytes). One sample = 4 bytes (2 bytes × 2 channels):

```
Positive offset (+N): Drive reads N samples late
  → N samples are missing at the end of the disc
  → N samples from the previous track bleed into the start

Negative offset (-N): Drive reads N samples early
  → N extra samples appear at the start
  → N samples missing at the end
```

The reference Plextor 14/32 drive was measured at **+679 samples** by EAC's author. All other drives are calibrated relative to this reference.

### Offset Determination Methods

**Method 1 — EAC's Internal Reference Database:**

EAC ships with a database of commercial CDs whose exact sector content is known. The ripped data is compared byte-by-byte against the reference to determine the shift:

```
offset = find_shift_that_maximises_match(ripped_data, reference_data)
```

Requires at least 2 separate reference CDs to confirm the result.

**Method 2 — AccurateRip Auto-Calibration:**

AccurateRip's database is much larger. When a recognized disc is inserted:

```
1. Rip track(s) at offset = 0
2. Compute AccurateRip checksum
3. Query AccurateRip database for matching checksums at various offsets
4. The offset that produces a matching checksum = drive's read offset
5. Store offset; use for all subsequent rips
```

### Offset Application During Ripping

Once the drive offset is known, it is compensated during extraction:

```
If drive offset = +N samples:
  Start reading N samples before the track start LBA
  Stop reading N samples before the track end LBA
  This shifts the extracted data back to the correct position

If drive offset = -N samples:
  Start reading |N| samples after the track start LBA
  Stop reading |N| samples after the track end LBA
```

For the first track and last track, this can require reading into the lead-in or lead-out area. Not all drives allow this. The **"Overread into Lead-in and Lead-Out"** EAC setting compensates:
- If overread into lead-in is not supported, zeros are prepended/appended
- EAC setting "Fill missing samples with silence" handles this gracefully

### Combined Read/Write Offset

For burning offset-corrected copies (not applicable to rip-only apps):

```
Combined offset = Read offset + Write offset
```

The write offset must be independently measured and is burner-specific.

---

## 5. Track Synchronization Technology

### Problem: Gapless/Live Albums

Standard ripping software extracts each track independently. On drives without Accurate Stream, the final samples of Track N and the first samples of Track N+1 may not perfectly align due to jitter in the seek to the new track position.

For studio albums with silence between tracks, this is inaudible. For **live albums or gapless recordings**, this creates an audible click at every track boundary.

### EAC's Solution

EAC uses the **"Synchronize between tracks"** option:

```
When ripping Track N+1:
  1. Read a small overlap region: the last ~588 samples of Track N's end region
  2. Read the first ~588 samples of Track N+1's start region
  3. Find the sample-accurate correlation between these two overlapping reads
  4. Use the correlation offset to precisely align the two tracks
  5. Remove the overlap; concatenate the corrected audio
```

This guarantees that if Track N ends with sample value X, Track N+1 begins with the immediately successive sample — no gap, no duplicate, no click.

This technique is only necessary on drives without Accurate Stream. Drives with Accurate Stream maintain position across track reads automatically.

---

## 6. Gap Detection Technology

### What Are Gaps?

The **pre-gap** (also called index 0 of a track) is silent (or sometimes audio) data between tracks. In a typical CD:

- Track 1 may have a hidden pre-gap audio track (negative time, accessed by rewinding before Track 1)
- All other tracks have a standard 2-second silence gap
- Live CDs/gapless recordings may have zero-sample gaps (or no gap at all)

The TOC only stores Track 1 start positions (`index 01`). Gaps and index markers must be detected by reading Q subchannel data.

### Gap Detection Methods A, B, C

EAC implements three gap detection strategies, each using different SCSI commands:

**Method A (fastest):**
Uses `READ SUBCHANNEL` command to interrogate the Q channel at binary-searched positions. Searches from the track start position backwards for the boundary between index 0 (gap) and index 1 (music start).

**Method B:**
Performs a linear scan of Q subchannel data from the previous track end to the current track start. Slower but more reliable on drives that report incorrect binary-search results.

**Method C (slowest, most accurate):**
Does a full sector-by-sector scan of Q subchannel data across the entire gap region. Used when Methods A and B return inconsistent results.

### Gap Handling Strategies

EAC offers three options for what to do with detected gaps:

| Option | Description |
|---|---|
| Append gap to previous track | Default; gap silence is appended to the end of Track N. Preserves gapless playback if tracks are played sequentially. |
| Prepend gap to next track | Gap silence is placed at the start of Track N+1. |
| Leave out gaps | Gaps are discarded entirely. Use only for studio albums where gaps are pure silence. |

---

## 7. AccurateRip Verification Protocol

AccurateRip (developed by "Spoon", hosted at accuraterip.com) is a crowdsourced database that lets rippers verify their rip against checksums submitted by thousands of other users.

The core insight: **if you rip the same CD on different drives and get the same checksum, your rip is almost certainly correct** — it's statistically impossible for different drives to introduce the same error in exactly the same position.

### 7.1 Disc Identification (TOC ID)

AccurateRip identifies a disc by a 3-part identifier derived from the TOC:

```c
// C++ pseudocode — AccurateRip disc ID calculation

struct ARTOCTRACK {
  BYTE Reserved;
  BYTE Adr;
  BYTE TrackNumber;
  BYTE Reserved2;
  BYTE Address[4];  // MSF address in BCD
};

struct ARTOC {
  WORD TOCLength;
  BYTE FirstTrack;
  BYTE LastTrack;
  ARTOCTRACK Tracks[100];
};

DWORD TrackOffsetsAdded       = 0;
DWORD TrackOffsetsMultiplied  = 1;

for (int i = FirstTrack; i <= LastTrack; i++) {
  DWORD LBA = MSFtoLBA(Tracks[i].Address);
  TrackOffsetsAdded      += LBA;
  TrackOffsetsMultiplied *= max(LBA, 1);  // Avoid multiply-by-zero
}

// Add the lead-out track
DWORD LeadOutLBA = MSFtoLBA(LeadOut.Address);
TrackOffsetsAdded      += LeadOutLBA;
TrackOffsetsMultiplied *= LeadOutLBA;

DWORD FreedBIdent = ComputeFreedbID(TOC);  // Standard freedb disc ID

// The 3-part disc identifier:
// disc_id_1 = TrackOffsetsAdded (hex)
// disc_id_2 = TrackOffsetsMultiplied (hex)
// disc_id_3 = FreedBIdent (hex)

// URL format:
// http://www.accuraterip.com/accuraterip/a/b/c/dARdisc_id_1-disc_id_2-disc_id_3.bin
// where a = last hex digit of disc_id_1
//       b = second to last hex digit
//       c = third to last hex digit
```

The disc ID uniquely identifies a specific pressing of a CD. Different pressings (different manufacturing runs with different master offsets) may have identical audio but different disc IDs — AccurateRip v2 addresses this.

### 7.2 AccurateRip v1 Checksum Algorithm

The ARv1 checksum is a 32-bit value calculated over the entire track's audio samples, with special handling at track boundaries:

```c
// AccurateRip v1 checksum pseudocode

DWORD ComputeARv1(
    DWORD* pAudioData,    // Track audio as 32-bit DWORD pairs (L+R interleaved)
    int    DataSize,      // Total bytes of audio
    int    TrackNumber,   // 1-indexed
    int    TotalTracks,
    int    SectorBytes    // = 2352
) {
    DWORD AR_CRC = 0;
    DWORD AR_CRCPosMulti = 1;  // Position multiplier (starts at 1, increments per DWORD)

    // Skip first 2,939 samples (~5 frames, ~0.013s) of Track 1
    // Skip last 2,939 samples of the last track
    // These boundary regions are excluded because drives struggle with
    // exact positioning at the very start and end of the disc

    int AR_CRCPosCheckFrom = 0;
    int AR_CRCPosCheckTo   = DataSize / sizeof(DWORD);

    if (TrackNumber == 1) {
        // Skip first 2939 samples = 5 sectors worth
        AR_CRCPosCheckFrom += (SectorBytes * 5) / sizeof(DWORD);
    }

    if (TrackNumber == TotalTracks) {
        // Skip last 2939 samples
        AR_CRCPosCheckTo -= (SectorBytes * 5) / sizeof(DWORD);
    }

    int DataDWORDSize = DataSize / sizeof(DWORD);
    for (int i = 0; i < DataDWORDSize; i++) {
        if (AR_CRCPosMulti >= AR_CRCPosCheckFrom &&
            AR_CRCPosMulti <= AR_CRCPosCheckTo) {
            AR_CRC += (AR_CRCPosMulti * pAudioData[i]);
        }
        AR_CRCPosMulti++;
    }

    return AR_CRC;
}
```

**Known flaw in ARv1:** Due to an optimization oversight, approximately 3% of audio data (specifically half the right-channel samples in a 65,536-sample cycle) is not fully included in the checksum. This reduces accuracy but does not make it useless.

### 7.3 AccurateRip v2 Checksum Algorithm

ARv2 corrects the ARv1 flaw with a different multiplication scheme:

```c
// AccurateRip v2 checksum pseudocode

DWORD ComputeARv2(
    DWORD* pAudioData,
    int    DataSize,
    // same boundary skipping rules as v1 apply
) {
    DWORD AC_CRCNEW = 0;
    DWORD MulBy = 1;  // Position counter

    // For each 32-bit DWORD value (packed L+R sample pair):
    for (int i = 0; i < DataSize / sizeof(DWORD); i++) {
        DWORD Value = pAudioData[i];

        // Full 64-bit multiplication to capture overflow accurately
        unsigned __int64 CalcCRCNEW = (unsigned __int64)Value *
                                      (unsigned __int64)MulBy;

        // Split into low and high 32-bit halves and accumulate both
        DWORD LOCalcCRCNEW = (DWORD)(CalcCRCNEW & 0xFFFFFFFF);
        DWORD HICalcCRCNEW = (DWORD)(CalcCRCNEW >> 32);

        AC_CRCNEW += HICalcCRCNEW;
        AC_CRCNEW += LOCalcCRCNEW;

        MulBy++;
    }

    return AC_CRCNEW;
}
```

ARv2 checksums are stored separately in the AccurateRip database (they appear as a different "pressing") and do not overwrite ARv1 data.

### 7.4 Database Query & Response Protocol

**Request (HTTP GET):**

```
GET http://www.accuraterip.com/accuraterip/{a}/{b}/{c}/dAR{id1}-{id2}-{id3}.bin
```

Where `a`, `b`, `c` are the last three hex nibbles of `disc_id_1` (the TrackOffsetsAdded value).

**Response (binary format):**

The response is a binary file containing one record per track, repeated for each pressing found in the database:

```
Disc Header (per pressing):
  [1 byte]  Track count

Per-track record (repeated for each track):
  [1 byte]  Confidence  (number of users who submitted this CRC; 200 = capped at 200+)
  [4 bytes] Track CRC   (AccurateRip checksum; 0x00000000 = never ripped, skip)
  [4 bytes] Offset CRC  (used for offset detection, can be ignored for basic verification)
```

**Verification Logic:**

```
computed_crcs = ComputeAllTrackCRCs(ripped_audio, v1_and_v2)
db_response   = FetchFromAccurateRip(disc_id)

for each track T:
  match_found = false
  for each pressing P in db_response:
    if db_response[P][T].TrackCRC == computed_crcs[T]:
      confidence = db_response[P][T].Confidence
      REPORT "Accurately ripped (confidence {confidence}) [CRC match]"
      match_found = true
      break

  if not match_found:
    REPORT "Cannot be verified — not in AccurateRip database"
    // This is NOT necessarily a failed rip; the pressing may not be in the DB
```

**Confidence Interpretation:**

| Confidence | Meaning |
|---|---|
| 0 | No submissions; cannot verify |
| 1–2 | Low confidence; single or few submissions |
| 3–10 | Moderate confidence; rip is likely correct |
| 10+ | High confidence; rip is almost certainly correct |
| 200 | Maximum reported; many hundreds of matching submissions |

---

## 8. CUETools DB (CTDB) Secondary Verification

CUETools Database (CTDB) provides an independent second verification system:

- Uses a different checksum algorithm (whole-disc CRC rather than per-track)
- CTDB TOC ID covers the entire disc image including all track boundaries
- Results reported as X/Y (X matching submissions out of Y total for this disc)
- A 10/10 CTDB result alongside AccurateRip confidence is the gold standard for a verified rip

The CTDB plugin for EAC is `CTDB EAC Plugin` and communicates with `db.cuetools.net`.

---

## 9. Test & Copy Verification (Local CRC)

When AccurateRip is unavailable or a disc is not in the database, EAC uses its internal **Test & Copy** (T&C) method:

**Process:**

```
Phase 1 — TEST:
  Rip entire track in secure mode
  Compute CRC32 of the extracted audio (not written to disk)
  Store as "Test CRC"

Phase 2 — COPY:
  Rip entire track again, independently, from scratch
  Compute CRC32 of the extracted audio
  Write to disk
  Store as "Copy CRC"

Verification:
  IF Test CRC == Copy CRC:
    High confidence the rip is stable and repeatable
    The two independent passes produced identical bit streams
  ELSE:
    Read errors are affecting different positions between passes
    Sector(s) are genuinely damaged or unreadable
    Mark as "Copy not OK"
```

**Limitation:** T&C cannot tell you whether the output is bit-perfect compared to the original master. It only confirms that two reads of the same disc produced the same result. A systematically wrong drive (constant offset, firmware bug) would pass T&C but fail AccurateRip.

---

## 10. EAC Log File Format

EAC generates a detailed log file per disc rip. The log should be produced by the Android app as well for interoperability with tools like CUETools.

### Log Structure

```
Exact Audio Copy V1.6 from DD. MMMM YYYY

EAC extraction logfile from DD. MMMM YYYY, HH:MM

Artist / Album title

Used drive  : [Drive Model] Adapter: [X] ID: [Y]

Read mode               : Secure
Utilize accurate stream : Yes
Defeat audio cache      : Yes
Make use of C2 pointers : No

Read offset correction                      : +NNN
Overread into Lead-In and Lead-Out          : No
Fill up missing offset samples with silence : Yes
Delete leading and trailing silent blocks   : No
Null samples used in CRC calculations       : Yes
Used interface                              : IOCTL

Used output format        : Internal WAV Routines / FLAC
Sample format             : 44.100 Hz; 16 Bit; Stereo

TOC of the extracted CD

     Track |   Start  |  Length  | Start sector | End sector
    ---------------------------------------------------------
        1  | 0:00.00  | 3:45.12  |         0    |   16886  
        2  | 3:45.12  | 4:12.08  |     16887    |   35744  
        ...

Track  1

     Filename [full path].flac

     Pre-gap length                        : 0:00:00

     Peak level                            100.0 %
     Extraction speed                      3.0 X
     Track quality                        100.0 %
     Test CRC                           XXXXXXXX
     Copy CRC                           XXXXXXXX
     Accurately ripped (confidence N)   [CHECKSUM] (AR vN)
     Copy OK

---

Track  2
...

==== Log checksum [SHA-256 of log content] ====
```

### Log Verification (SHA-256)

EAC (v1.0+) appends a SHA-256 hash of the log content to prevent tampering. Music databases and torrent communities use this to detect falsified logs.

---

## 11. Android Implementation Architecture

### 11.1 USB Host Mode & Drive Access

External USB CD drives connect to Android via the **USB Host API** (available since Android 3.1, API level 12). Practically, OTG (USB On-The-Go) requires:

- USB-C or Micro-USB OTG cable/adapter
- Powered USB hub (most CD drives draw >500mA, exceeding OTG power limits)
- Android device with USB Host Mode enabled in hardware (not all budget devices)

**USB Host API Access Pattern:**

```kotlin
// Request permission and acquire USB device
val usbManager = getSystemService(USB_SERVICE) as UsbManager
val deviceList = usbManager.deviceList

for ((_, device) in deviceList) {
    if (isCdDriveDevice(device)) {
        if (usbManager.hasPermission(device)) {
            openCdDrive(device)
        } else {
            val permIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), FLAG_MUTABLE)
            usbManager.requestPermission(device, permIntent)
        }
    }
}

// Identify CD drives: USB Class 0x08 (Mass Storage), Subclass 0x02 or 0x06 (SCSI)
fun isCdDriveDevice(device: UsbDevice): Boolean {
    for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true  // Further identification via INQUIRY command
        }
    }
    return false
}
```

### 11.2 MMC/SCSI Command Interface

CD drives use the **SCSI MMC (Multi-Media Commands)** standard over USB Bulk-Only Transport (BOT):

**USB BOT Transaction Structure:**

```
1. Host → Device: 31-byte Command Block Wrapper (CBW)
   - dCBWSignature:      0x43425355 ("USBC")
   - dCBWTag:            unique 32-bit tag for this command
   - dCBWDataTransferLength: expected data length
   - bmCBWFlags:         0x80 = data IN (device to host)
   - bCBWLUN:            0x00 (Logical Unit Number)
   - bCBWCBLength:       length of CDB (6–16 bytes)
   - CBWCB:              the actual SCSI CDB

2. Device → Host: Data phase (if applicable)

3. Device → Host: 13-byte Command Status Wrapper (CSW)
   - dCSWSignature:  0x53425355 ("USBS")
   - dCSWTag:        matches CBW tag
   - dCSWDataResidue: difference between expected and actual data transferred
   - bCSWStatus:    0x00 = success, 0x01 = failed, 0x02 = phase error
```

### 11.3 CD-DA Read Commands

**Primary Read Command — READ CD (0xBE):**

This is the correct command for reading raw audio sectors with optional subchannel data:

```
CDB (12 bytes):
  [0]  0xBE          Operation Code: READ CD
  [1]  0x00          Expected Sector Type: 0b000 = any, 0b001 = CD-DA audio
  [2]  LBA[31:24]    Starting LBA (big-endian)
  [3]  LBA[23:16]
  [4]  LBA[15:8]
  [5]  LBA[7:0]
  [6]  Length[23:16] Transfer length in sectors (big-endian)
  [7]  Length[15:8]
  [8]  Length[7:0]
  [9]  0xF8          Sync=1, Header=11 (all), UserData=1, EDC_ECC=1, C2=0b00 (no C2)
                     For C2 error pointers: use 0xF9 (C2 bits enabled)
  [10] 0x01          Subchannel selection: 0x00=none, 0x01=raw 96-byte P-W subchannel
                     0x02=Q subchannel only, 0x04=R-W subchannel
  [11] 0x00          Reserved (pad)
```

**Response per sector:**
- With 0xF8 and no subchannel: 2,352 bytes of raw audio
- With 0xF9 (C2 flags): 2,352 bytes audio + 294 bytes C2 flags + optional pad = 2,646 bytes
- With 0x01 subchannel: 2,352 bytes audio + 96 bytes subchannel = 2,448 bytes

**Read TOC Command (0x43):**

```
CDB (10 bytes):
  [0]  0x43          Operation Code: READ TOC/PMA/ATIP
  [1]  0x02          MSF bit set (return addresses in MSF format)
  [2]  0x00          Format: 0x00 = Formatted TOC, 0x01 = Multi-session, 0x02 = Raw TOC
  [3–6] 0x00         Reserved
  [7]  0x00          Track/Session number (0 = return all)
  [8]  alloc_len[1]  Allocation length (how many bytes to return)
  [9]  alloc_len[0]
  [10] 0x00          Control
```

**INQUIRY Command (0x12):**

Used during drive identification to get model string, vendor, firmware revision, and (via VPD pages) supported feature pages:

```
CDB (6 bytes):
  [0]  0x12    INQUIRY
  [1]  0x00    EVPD bit = 0 (standard inquiry)
  [2]  0x00    Page code (0 for standard)
  [3]  0x00    Reserved
  [4]  0x24    Allocation length (36 bytes minimum for standard inquiry)
  [5]  0x00    Control
```

**GET CONFIGURATION Command (0x46):**

Used to query drive features including supported DAE rate, Accurate Stream capability, and C2 support:

```
CDB (10 bytes):
  [0]  0x46    GET CONFIGURATION
  [1]  0x02    RT field: 0x02 = return feature headers for all current features
  [2–3] 0x00   Starting Feature Number
  [4–7] 0x00   Reserved
  [8]  alloc   Allocation length
  [9]  0x00    Control

Feature Code 0x0107 = CD Read Feature (contains Accurate Stream bit)
Feature Code 0x0108 = CD Track at Once
Feature Code 0x010D = CD-RW Write
Feature Code 0x0014 = C2 Error Pointers Feature
```

**TEST UNIT READY (0x00):**

Poll disc-ready status before issuing read commands:

```
CDB (6 bytes): [0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
Returns: GOOD (ready) or CHECK CONDITION (not ready; check SENSE data)
```

**REQUEST SENSE (0x03):**

Retrieve extended error information after a CHECK CONDITION:

```
CDB (6 bytes): [0x03, 0x00, 0x00, 0x00, 0x12, 0x00]
Response includes:
  - Sense Key (0x00=No Sense, 0x01=Recovered, 0x02=Not Ready, 0x03=Medium Error...)
  - Additional Sense Code (ASC)
  - Additional Sense Code Qualifier (ASCQ)
```

### 11.4 Drive Feature Detection

Perform this once per drive model and cache the results:

```kotlin
data class DriveCapabilities(
    val modelString: String,
    val vendorString: String,
    val supportsAccurateStream: Boolean,
    val supportsC2ErrorPointers: Boolean,
    val estimatedCacheSizeSectors: Int,    // 0 = no cache detected
    val supportedReadCommands: List<ReadCommand>,
    val maxDAESpeed: Int,                  // in multiples of 1x (150 KB/s)
    val readOffset: Int?                   // null until calibrated
)

fun detectDriveCapabilities(device: UsbDevice): DriveCapabilities {
    // 1. INQUIRY — get model/vendor strings
    // 2. GET CONFIGURATION — check feature codes for AccurateStream, C2
    // 3. Attempt READ CD with 1 sector at various CDBs (READ CD 0xBE, READ(10) 0x28)
    //    to find supported read commands
    // 4. Probe cache: read sector N, then re-read sector N — if identical in <1ms,
    //    cache is active; estimate size by reading increasing distances
    // 5. Test C2: read a known-good sector with C2 flags enabled; verify flags = 0
    //    and that re-read matches
}
```

### 11.5 Recommended Architecture Stack

```
┌────────────────────────────────────────────────────┐
│                 Android App UI Layer                │
│  (Compose/Views, progress, track list, log viewer) │
└────────────────────────┬───────────────────────────┘
                         │
┌────────────────────────▼───────────────────────────┐
│              Ripping Orchestrator Service           │
│  (Coroutines/Foreground Service, state machine)    │
└──┬─────────────────────┬──────────────────────┬────┘
   │                     │                      │
┌──▼──────────┐  ┌───────▼──────┐  ┌────────────▼────┐
│  Secure     │  │  AccurateRip │  │ Metadata/CDDB   │
│  Extractor  │  │  Verifier    │  │ Fetcher         │
└──┬──────────┘  └──────────────┘  └─────────────────┘
   │
┌──▼──────────────────────────┐
│    Drive Abstraction Layer  │
│  (offset correction,        │
│   cache-busting, overlap    │
│   read, sector retry logic) │
└──┬──────────────────────────┘
   │
┌──▼──────────────────────────┐
│   SCSI/MMC Command Layer    │
│  (READ CD, READ TOC,        │
│   GET CONFIG, INQUIRY...)   │
└──┬──────────────────────────┘
   │
┌──▼──────────────────────────┐
│  USB BOT Transport Layer    │
│  (CBW/CSW, bulk endpoints,  │
│   UsbDeviceConnection)      │
└──┬──────────────────────────┘
   │ USB OTG
┌──▼──────────────────────────┐
│    External USB CD Drive    │
└─────────────────────────────┘
```

---

## 12. Android Secure Ripping Pipeline

### Complete Ripping Workflow

```
PHASE 0: DISC SETUP
  1. Detect USB device → identify as CD drive via INQUIRY
  2. TEST UNIT READY → wait for disc ready
  3. READ TOC → parse track list, LBA positions
  4. GET CONFIGURATION → determine AccurateStream, C2 support
  5. Probe cache (reads-at-same-position timing test)
  6. Look up drive offset (AccurateRip DB or user-entered)
  7. Fetch AccurateRip data for disc (background, non-blocking)
  8. Fetch MusicBrainz/freedb metadata (background)

PHASE 1: TRACK EXTRACTION (per track)
  For each audio track T:

  1. COMPUTE READ RANGE with offset correction:
     start_sector = track_start_lba - (offset_samples / 588)
     end_sector   = track_end_lba   - (offset_samples / 588)
     // Clamp to valid range; fill with zeros if overreading is not supported

  2. FOR EACH SECTOR S in [start_sector .. end_sector]:
     a. READ SECTOR (secure mode):
        reads = []
        batch = 0
        WHILE batch < max_batches:  // 1, 3, or 5
          FOR attempt = 1 to 16:
            IF needs_cache_bust:
              READ far-away decoy sector to clear cache
            data = ReadCD(S, with_c2=use_c2_mode)
            reads.append(data)
          majority = find_majority(reads)
          IF count(majority) >= 8:
            ACCEPT majority as correct sector data
            BREAK
          batch++
        IF no majority found:
          LOG "Suspicious position at MSF [time]"
          USE best_available(reads)

     b. TRACK SYNCHRONIZATION (if gapless and non-accurate-stream):
        Overlap-read last 2 sectors of previous track
        Correlate with first 2 sectors of current track
        Align and trim

  3. COMPUTE LOCAL CRC32 of all extracted sectors (for Test & Copy)
  4. WRITE to temporary file
  5. REPEAT EXTRACTION (Test phase) for CRC comparison
  6. COMPARE Test CRC vs Copy CRC → log result

PHASE 2: VERIFICATION
  1. Compute AccurateRip v1 and v2 checksums per track
  2. Compare against fetched AccurateRip database entries
  3. Log confidence level per track
  4. Compute CTDB whole-disc checksum; query CTDB
  5. Log CTDB result

PHASE 3: ENCODING & OUTPUT
  1. Apply offset correction (shift samples, zero-fill if needed)
  2. Encode to FLAC (lossless, recommended) or WAV
  3. Embed metadata tags (MusicBrainz/freedb data)
  4. Embed cover art if available
  5. Write CUE sheet
  6. Write log file (EAC-format compatible)
  7. Compute SHA-256 of log; append to log
```

### Sector Reading State Machine

```kotlin
enum class SectorResult { SUCCESS, SUSPICIOUS, UNREADABLE }

data class ReadSectorResult(
    val data: ByteArray,
    val quality: SectorResult,
    val readsRequired: Int
)

fun readSectorSecure(lba: Int, driveCapabilities: DriveCapabilities): ReadSectorResult {
    val maxBatches = userSettings.errorRecoveryQuality  // 1, 3, or 5

    for (batch in 0 until maxBatches) {
        val reads = mutableListOf<ByteArray>()

        repeat(16) {
            if (driveCapabilities.estimatedCacheSizeSectors > 0) {
                bustCache(lba)  // Read decoy sector far away
            }
            reads.add(readCDSector(lba))
        }

        val majority = findMajority(reads)
        if (majority.count >= 8) {
            return ReadSectorResult(majority.data, SectorResult.SUCCESS, (batch + 1) * 16)
        }
    }

    // Could not achieve majority — mark suspicious
    val bestGuess = findMostCommon(reads)
    return ReadSectorResult(bestGuess, SectorResult.SUSPICIOUS, maxBatches * 16)
}

fun bustCache(targetLba: Int) {
    val decoyLba = (targetLba + 10000).coerceAtMost(totalSectors - 1)
    readCDSector(decoyLba)  // Force cache to load far-away data
}
```

---

## 13. Drive Offset Detection on Android

Since AccurateRip requires a correctly-calibrated offset, the app must handle offset detection:

### Method 1: AccurateRip Auto-Calibration

```
1. Insert a popular, well-known commercial CD (many AR submissions expected)
2. READ TOC → compute disc ID → fetch AccurateRip data
3. If disc is in AccurateRip DB:
   a. Rip all tracks at offset = 0
   b. Compute AR checksums
   c. For each pressing P in DB:
      For offset_guess in range(-5 * 588, +5 * 588, step=1):  // ±5 sectors
        shifted_checksums = ComputeARChecksums(audio, offset_guess)
        if shifted_checksums match DB entry P:
          FOUND offset = offset_guess
          STORE as drive offset
          RETURN
4. If no match: try a different CD and repeat
```

### Method 2: Manual Entry

- Show the user the drive model string (from INQUIRY)
- Link to the AccurateRip offset database at accuraterip.com/driveoffsets.asp
- Allow user to enter a known offset value

### Method 3: Reference CD Comparison

The app ships with a small internal database of reference CDs (matching EAC's built-in list). If the user inserts one of these CDs, the offset is determined by byte-level comparison.

---

## 14. Error Handling & Recovery Strategy

### Disc Condition Classification

| Symptom | Likely Cause | Strategy |
|---|---|---|
| Track quality 100%, AR match | Clean disc, good drive | Accept; log as verified |
| Track quality <100%, AR match | Minor errors recovered | Accept; log quality percentage |
| Track quality <100%, no AR match | Disc damage, no DB entry | Log suspicious positions; user decides |
| Sync errors (frequent) | Heavy scratches, dirty disc | Clean disc; retry; try burst mode |
| All reads return silence | Wrong read command detected | Auto-detect alternate command; fall back to READ(10) |
| Drive hangs mid-read | Firmware bug / damaged sector | Implement read timeout (e.g. 30s); skip sector with zero-fill |
| Consistent read failures on 1 track | Physical scratch | Slow down read speed; retry; offer to rip from burst with warning |

### Speed Reduction

On persistent errors, EAC reduces drive speed to improve read reliability. On Android:

```kotlin
// SET CD SPEED command (0xBB)
fun setDriveSpeed(speedKBps: Int) {
    val cdb = ByteArray(12)
    cdb[0] = 0xBB.toByte()   // SET CD SPEED
    cdb[2] = (speedKBps shr 8 and 0xFF).toByte()
    cdb[3] = (speedKBps and 0xFF).toByte()
    sendScsiCommand(cdb, null)
}

// Standard speeds: 1x=176, 2x=352, 4x=705, 8x=1411 KB/s
// For difficult discs: try 2x or even 1x
```

### Suspicious Position Handling

When a sector cannot achieve 8-of-16 majority:

1. Log the MSF timecode of the suspicious position
2. Use the most common result from all reads as the best guess
3. Continue ripping (do not abort)
4. At end of rip, report total number of suspicious positions
5. Offer a playback preview of the suspicious region so the user can decide if the error is audible

---

## 15. Output Formats & Metadata

### Recommended Formats

| Format | Use Case | Notes |
|---|---|---|
| FLAC | Primary archival | Lossless; supports all metadata; widely supported |
| WAV | Legacy compatibility | No embedded metadata in standard WAV; use external cue sheet |
| ALAC | Apple ecosystem | Lossless; embedded in M4A container |

### CUE Sheet

The CUE sheet is critical for archival — it preserves track timing and can be used to reconstruct the exact disc layout:

```
PERFORMER "Artist Name"
TITLE "Album Title"
FILE "Album Title.flac" WAVE
  TRACK 01 AUDIO
    TITLE "Track Title 1"
    PERFORMER "Artist"
    INDEX 00 00:00:00   (pre-gap if present)
    INDEX 01 00:02:00   (track start)
  TRACK 02 AUDIO
    TITLE "Track Title 2"
    INDEX 00 03:45:10   (gap)
    INDEX 01 03:47:00   (music start)
```

### FLAC Tag Mapping

| FLAC Tag | Source |
|---|---|
| TITLE | Track title from MusicBrainz/freedb |
| ARTIST | Track artist |
| ALBUMARTIST | Album-level artist |
| ALBUM | Album title |
| DATE | Release year |
| TRACKNUMBER | Track number |
| TOTALTRACKS | Total tracks on disc |
| DISCNUMBER | Disc number in set |
| GENRE | Genre |
| MUSICBRAINZ_TRACKID | MusicBrainz track MBID |
| ACCURATERIPRESULT | "Rip accurate, confidence N" |
| LOG | Embedded rip log (optional) |

### Metadata Sources (Priority Order)

1. **MusicBrainz** — highest accuracy, open database, free API
2. **freedb** (via gnudb.org mirror) — legacy standard, still widely populated
3. **GnuDB** — community-maintained freedb successor
4. **Manual entry** — user override

**freedb Disc ID Calculation:**

```c
DWORD ComputeFreedbID(ARTOC* toc) {
    int n = 0;
    for (int i = FirstTrack; i <= LastTrack; i++) {
        int t = MSFtoSeconds(toc->Tracks[i].Address);
        while (t > 0) {
            n += (t % 10);
            t /= 10;
        }
    }
    int t = MSFtoSeconds(LeadOut) - MSFtoSeconds(Tracks[FirstTrack]);
    return ((n % 0xFF) << 24) | (t << 8) | TrackCount;
}
```

---

## 16. Performance Considerations

### Read Speed vs. Accuracy Trade-offs

| Mode | Speed | Reliability |
|---|---|---|
| Burst (no verification) | 8–52× | Low (errors silently included) |
| Secure + Accurate Stream | 2–8× | Very high |
| Secure + Cache Defeat | 1–3× | Very high (slower due to cache busting) |
| Secure + C2 (reliable drive only) | 4–12× | Very high (nearly burst speed on clean discs) |

### USB Throughput

- USB 2.0 HS (480 Mbit/s): ~60 MB/s max theoretical; practical ~35 MB/s
- A raw audio sector = 2,352 bytes; at 75 sectors/sec = 176.4 KB/s per 1×
- USB overhead is negligible; bottleneck is the CD drive's DAE speed and re-read count

### Android-Specific Performance Notes

- Use a dedicated background thread (not the main thread) for all USB I/O
- Use `Foreground Service` to prevent the OS from killing the rip mid-operation
- Buffer sectors in memory (ring buffer ~50 sectors) to pipeline reads and processing
- Compute AccurateRip checksums incrementally as sectors arrive (not post-hoc)
- Use `WakeLock` to prevent device sleep during long rips

### Battery Impact

A full 70-minute album rip in secure mode on a powered USB hub will draw negligible power from the Android device (the CD drive is powered externally). The main drain is the CPU for verification computations — this is modest (a few Watts).

---

## 17. Feature Parity Matrix: EAC vs Android App

| Feature | EAC | Android App | Notes |
|---|---|---|---|
| Secure Mode (multi-pass majority) | ✅ | ✅ Must-have | Core algorithm |
| Accurate Stream detection | ✅ | ✅ Must-have | Via GET CONFIGURATION |
| Cache defeat | ✅ | ✅ Must-have | Overread decoy sectors |
| C2 error pointer mode | ✅ (optional) | ⚠️ Optional | Only for verified drives |
| Read offset correction | ✅ | ✅ Must-have | Calibrated via AccurateRip |
| Overread lead-in/lead-out | ✅ | ✅ Should-have | Silence fill fallback |
| Track synchronization | ✅ | ✅ Should-have | For gapless albums |
| Gap detection (A/B/C) | ✅ | ✅ Should-have | Q subchannel scan |
| AccurateRip v1 verification | ✅ | ✅ Must-have | CRC match + confidence |
| AccurateRip v2 verification | ✅ | ✅ Must-have | Improved algorithm |
| Test & Copy (local CRC32) | ✅ | ✅ Must-have | Independent double-pass |
| CUETools DB verification | ✅ (plugin) | ✅ Should-have | Secondary verification |
| FLAC output | ✅ | ✅ Must-have | Primary archival format |
| WAV output | ✅ | ✅ Should-have | Legacy compatibility |
| CUE sheet generation | ✅ | ✅ Must-have | For disc archival |
| EAC-compatible log file | ✅ | ✅ Must-have | Log SHA-256 checksum |
| MusicBrainz metadata | ✅ (plugin) | ✅ Must-have | Primary metadata source |
| freedb/gnudb metadata | ✅ | ✅ Should-have | Fallback |
| CD-Text reading | ✅ | ⚠️ Optional | Not all drives support |
| Speed reduction on errors | ✅ | ✅ Should-have | SET CD SPEED command |
| Burst mode (fast, no verify) | ✅ | ⚠️ Optional | For scratch discs only |
| Per-sector suspicious pos. logging | ✅ | ✅ Must-have | MSF timecode logging |
| Drive offset auto-detection | ✅ | ✅ Must-have | AccurateRip calibration |
| Multi-disc set support | ✅ | ✅ Should-have | DISCNUMBER tag |
| Hidden track detection | ✅ | ⚠️ Optional | Pre-gap audio (Track 0) |
| Log SHA-256 signing | ✅ | ✅ Must-have | Log integrity verification |

---

## Appendix A: Key SCSI MMC Command Reference

| Command | Opcode | Purpose |
|---|---|---|
| TEST UNIT READY | 0x00 | Check if drive is ready |
| INQUIRY | 0x12 | Get device identification |
| REQUEST SENSE | 0x03 | Get extended error info |
| READ TOC/PMA/ATIP | 0x43 | Read table of contents |
| READ CD | 0xBE | Read audio sectors (primary) |
| READ(10) | 0x28 | Alternative sector read |
| SET CD SPEED | 0xBB | Set drive read speed |
| GET CONFIGURATION | 0x46 | Query drive feature support |
| MODE SENSE(6) | 0x1A | Get drive mode parameters |
| READ SUB-CHANNEL | 0x42 | Read Q subchannel (gap detection) |
| PLAY AUDIO(10) | 0x45 | Drive-assisted gap detection |

---

## Appendix B: Error Recovery Quality vs. Read Count

| EAC Setting | Batches | Max reads/sector | EAC equivalent |
|---|---|---|---|
| Minimum | 1 | 16 | "Low" |
| Medium | 3 | 48 | "Medium" |
| Maximum | 5 | 80 (+initial) = 82 | "High" |

---

## Appendix C: AccurateRip URL Format Example

For a disc with:
- `disc_id_1` = `0x001C5390`
- `disc_id_2` = `0x011E9241`
- `disc_id_3` = `0xAD0E610D`

Last 3 hex nibbles of `disc_id_1`: `3`, `9`, `0`

URL:
```
http://www.accuraterip.com/accuraterip/0/9/3/dAR001c5390-011e9241-ad0e610d.bin
```

---

## Appendix D: Suggested Kotlin/Java Dependencies

| Library | Purpose |
|---|---|
| `usb-serial-for-android` (or direct Android USB API) | USB host communication |
| `libusb` (via NDK) | Low-level SCSI passthrough if needed |
| `jflac` / `jaudiotagger` | FLAC encoding and tag writing |
| OkHttp / Ktor | AccurateRip and MusicBrainz HTTP queries |
| `kotlinx.coroutines` | Async rip pipeline |
| Android `ForegroundService` | Long-running rip process |
| Android `WakeLock` | Prevent sleep during rip |

---

*This specification is based on technical analysis of Exact Audio Copy (EAC) v1.6, AccurateRip protocol documentation, MMC-6 SCSI command set specifications, and the Red Book (IEC 60908) CD-DA standard.*
