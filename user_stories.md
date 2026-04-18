# BitPerfect — User Stories

> **How to use this file**
> Mark a story done by changing `- [ ]` to `- [x]` next to its checkbox.
> Each epic groups stories that can be tackled independently in priority order.

---

## Epic 1 — USB CD Drive Detection & Connection

> The app must discover, connect to, and maintain a stable session with an external USB CD drive via Android's USB Host API.

---

### Story 1.1 — Enumerate connected USB devices
- [x] **Done**

**As a** user who has plugged in a USB CD drive,
**I want** the app to automatically detect it when it is connected,
**so that** I don't have to configure anything manually.

**Acceptance criteria:**
- The app registers a `BroadcastReceiver` for `USB_DEVICE_ATTACHED` and `USB_DEVICE_DETACHED`.
- On attachment, the app iterates `UsbManager.deviceList` and checks each device's interfaces for `USB_CLASS_MASS_STORAGE`.
- A CD drive candidate is confirmed by issuing an `INQUIRY (0x12)` SCSI command and checking the Peripheral Device Type byte (value `0x05` = CD-ROM).
- If no CD drive is found, the UI shows an empty/idle state with a "Connect a USB CD drive" prompt.

**Technical notes:**
- Use `isCdDriveDevice()` helper from `secure.md §11.1`.
- Class 0x08, Subclass 0x02 / 0x06 indicates SCSI Mass Storage.

---

### Story 1.2 — Request USB permission from the user
- [x] **Done**

**As a** user,
**I want** to be asked for permission to access the CD drive once,
**so that** the app can communicate with it securely.

**Acceptance criteria:**
- If `UsbManager.hasPermission(device)` is false, the app calls `requestPermission()` with a `PendingIntent`.
- A system dialog is shown to the user asking to allow access.
- On grant, the app immediately proceeds to open the drive.
- On denial, the app shows a clear error message explaining why access is needed.
- Permission state is remembered; the app does not re-prompt on subsequent launches for the same device (Android handles this via the permission grant).

---

### Story 1.3 — Open and maintain a USB bulk-transfer connection
- [x] **Done**

**As a** developer,
**I want** a robust USB BOT (Bulk-Only Transport) transport layer,
**so that** all SCSI commands can be sent and responses received reliably.

**Acceptance criteria:**
- The transport layer wraps `UsbDeviceConnection.bulkTransfer()` for both OUT (CBW) and IN (data + CSW) endpoints.
- CBW is constructed correctly: signature `0x43425355`, unique tag, correct transfer length, LUN `0x00`.
- CSW is parsed and validated: signature `0x53425355`, matching tag, `bCSWStatus == 0x00` for success.
- Phase errors (`bCSWStatus == 0x02`) trigger a USB reset + retry.
- A configurable timeout (default 30 s) is applied to every `bulkTransfer` call.
- The layer is unit-testable with a mock `UsbDeviceConnection`.

**Technical notes:**
- See `secure.md §11.2` for full CBW/CSW byte layout.

---

### Story 1.4 — Detect drive disconnection and notify the user
- [x] **Done**

**As a** user,
**I want** the app to gracefully handle an unexpected drive disconnection,
**so that** any in-progress operation is cancelled cleanly without a crash.

**Acceptance criteria:**
- `USB_DEVICE_DETACHED` broadcast cancels any in-progress coroutine/job.
- The UI transitions to an idle/disconnected state with a toast or snackbar notification.
- If a rip was in progress, partial data is not silently left on disk without a warning.
- Reconnecting the same drive resumes from the idle state (no permission re-prompt required if previously granted for that device).

---

## Epic 2 — Drive Status & Tray Control

> The app must poll and display the drive's real-time status (ready, tray open, no disc, etc.) and allow the user to open/close the tray.

---

### Story 2.1 — Poll drive ready status
- [x] **Done**

**As a** user,
**I want** the app to continuously show whether the drive is ready, empty, or has a disc,
**so that** I always know the current state before starting a rip.

**Acceptance criteria:**
- The app issues `TEST UNIT READY (0x00)` on a configurable interval (default 2 s) via a background coroutine.
- Response `GOOD` → drive is ready with a disc.
- `CHECK CONDITION` + `REQUEST SENSE (0x03)` is issued to classify the state:
  - Sense Key `0x02` (Not Ready), ASC `0x3A` = No disc / tray open.
  - Sense Key `0x02`, ASC `0x04` = Drive becoming ready (spinning up).
  - Other sense keys logged for diagnostics.
- The UI displays one of: `No Drive`, `No Disc`, `Tray Open`, `Spinning Up`, `Ready`.
- Polling stops while a rip is in progress.

---

### Story 2.2 — Open and close the drive tray
- [x] **Done**

**As a** user,
**I want** to open and close the CD tray from within the app,
**so that** I don't have to physically press the drive's eject button.

**Acceptance criteria:**
- Tapping an "Eject" button sends `START STOP UNIT (0x1B)` with `LoEj=1, Start=0` (eject).
- Tapping a "Load/Close" button sends `START STOP UNIT (0x1B)` with `LoEj=1, Start=1` (load).
- Both buttons are disabled and show a spinner while the command is in flight.
- If the drive does not support software tray control, the buttons are hidden and a note is shown.
- Eject is disabled while a rip is in progress.

---

### Story 2.3 — Display drive model and capability summary
- [x] **Done**

**As a** user,
**I want** to see basic information about the connected drive,
**so that** I can confirm it is recognised correctly and understand its capabilities.

**Acceptance criteria:**
- On successful connection, the app issues `INQUIRY (0x12)` and `GET CONFIGURATION (0x46)`.
- The UI shows: Vendor string, Model string, Firmware revision.
- The UI shows capability badges: `Accurate Stream ✓/✗`, `C2 Error Pointers ✓/✗`, `Cache detected ✓/✗`.
- `DriveCapabilities` data class (per `secure.md §11.4`) is populated and stored in a ViewModel.
- Results are cached per device VID+PID so `INQUIRY` is not re-issued on every app launch.

---

## Epic 3 — Read TOC & Display Track List

> Once a disc is detected, the app must read the Table of Contents and display it in a clear, readable track list.

---

### Story 3.1 — Issue READ TOC command and parse the response
- [x] **Done**

**As a** developer,
**I want** a `TocReader` component that sends `READ TOC/PMA/ATIP (0x43)` and parses the binary response,
**so that** all other features can consume a structured `DiscToc` model.

**Acceptance criteria:**
- `READ TOC` is issued with MSF bit set (format byte `0x02`) requesting all tracks.
- The response header (total length, first track, last track) is parsed correctly.
- Each track descriptor is parsed: track number, control/ADR byte, MSF address.
- Audio tracks (ADR = 0) and data tracks (ADR = 4) are distinguished.
- Lead-out track (track number `0xAA`) LBA is extracted for AccurateRip disc ID calculation.
- LBA is calculated from MSF as: `LBA = (M × 60 + S) × 75 + F − 150`.
- The resulting `DiscToc` model includes: track count, list of `TrackEntry(number, startLba, durationSectors, isAudio)`, lead-out LBA, total disc duration.
- Unit tests cover a known TOC binary fixture.

---

### Story 3.2 — Display the track list in the UI
- [x] **Done**

**As a** user,
**I want** to see the full list of tracks on the inserted disc immediately after it is detected,
**so that** I can review what is on the disc before ripping.

**Acceptance criteria:**
- The track list screen shows a numbered row per track: track number, duration (M:SS), and an audio/data badge.
- Total disc duration is displayed in a summary row at the bottom.
- The list appears within 2 seconds of the disc becoming ready.
- If the disc is ejected, the track list clears and returns to the idle state.
- The UI matches the Material Design 3 style established in `design_spec.md` (M3, dynamic colour, rounded cards).

---

### Story 3.3 — Handle discs with no readable TOC gracefully
- [x] **Done**

**As a** user,
**I want** to see a clear error message if the disc cannot be read,
**so that** I am not left with a blank screen and no explanation.

**Acceptance criteria:**
- If `READ TOC` returns a CHECK CONDITION, sense data is retrieved and interpreted.
- Common cases are surfaced as human-readable messages: "Disc unreadable — try cleaning it", "Data disc inserted — audio ripping requires an audio CD", "Drive not ready yet — please wait".
- A "Retry" button re-issues `TEST UNIT READY` and then `READ TOC`.

---

## Epic 4 — MusicBrainz Disc Lookup

> Using the TOC data, the app computes the correct disc ID and queries MusicBrainz to retrieve album and track metadata.

---

### Story 4.1 — Compute the AccurateRip / freedb disc IDs
- [x] **Done**

**As a** developer,
**I want** functions that derive the AccurateRip 3-part disc ID and the freedb disc ID from a `DiscToc`,
**so that** both the AccurateRip and MusicBrainz lookups have the correct identifiers.

**Acceptance criteria:**
- `computeAccurateRipId(toc: DiscToc)` returns `AccurateRipDiscId(id1, id2, id3)` using the algorithm in `secure.md §7.1`.
- `computeFreedbId(toc: DiscToc)` returns a `Long` using the digit-sum algorithm in `secure.md §15`.
- The MusicBrainz disc ID is computed as the standard MB DiscID (SHA-1 of the TOC string per MusicBrainz spec).
- All three functions are covered by unit tests using a known reference TOC.

---

### Story 4.2 — Query MusicBrainz for disc releases
- [x] **Done**

**As a** user,
**I want** the app to automatically look up the inserted CD on MusicBrainz,
**so that** I see the album title, artist, and track names without typing anything.

**Acceptance criteria:**
- The MusicBrainz lookup is triggered automatically when a TOC is successfully parsed.
- The query uses the MB lookup endpoint: `https://musicbrainz.org/ws/2/discid/{mb_disc_id}?inc=recordings+artists+release-groups&fmt=json`.
- The `User-Agent` header is set to `BitPerfect/<version> ( <contact_url> )` per MusicBrainz policy.
- Results are fetched in a background coroutine; the UI shows a loading indicator.
- On success, a list of matching `Release` candidates is stored in the ViewModel.
- On network failure or 404, a clear message is shown with a manual retry button.
- Results are cached by disc ID for the session to avoid duplicate network calls.

---

### Story 4.3 — Display MusicBrainz results and let the user select a release
- [x] **Done**

**As a** user,
**I want** to see the album art, title, artist, and year for each matching release found on MusicBrainz,
**so that** I can pick the correct pressing before ripping.

**Acceptance criteria:**
- If exactly one release matches, it is automatically applied and the track names populate the track list.
- If multiple releases match, a bottom sheet or dialog presents each option with: album art thumbnail (from Cover Art Archive), album title, artist, year, country/label.
- The user can tap a release to select it; the track list updates with that release's track names.
- The user can also dismiss the sheet and proceed with unnamed tracks.
- Cover art is loaded asynchronously using Coil (per `design_spec.md §2`).

---

### Story 4.4 — Merge MusicBrainz metadata with TOC track list
- [x] **Done**

**As a** developer,
**I want** a `MetadataMerger` that combines TOC data with a chosen MusicBrainz `Release`,
**so that** each `TrackEntry` carries its title, artist, MBID, and other tags ready for FLAC encoding.

**Acceptance criteria:**
- Track titles, artists, MBIDs (`MUSICBRAINZ_TRACKID`) are mapped by track number.
- Album-level fields are populated: `ALBUM`, `ALBUMARTIST`, `DATE`, `TOTALTRACKS`, `DISCNUMBER`.
- If the track counts don't match (e.g. hidden bonus disc), a warning is surfaced to the user.
- The merged model is exposed as `List<TrackMetadata>` in the ViewModel.

---

### Story 4.5 — Fall back to freedb/GnuDB if MusicBrainz returns no results
- [x] **Done**

**As a** user,
**I want** the app to try freedb/GnuDB as a fallback if MusicBrainz has no entry for my disc,
**so that** older or obscure CDs still have a chance of being identified.

**Acceptance criteria:**
- If MusicBrainz returns a 404 or zero releases, the app automatically queries `https://gnudb.org/` using the freedb disc ID.
- The query uses the standard freedb CDDB protocol (HTTP query format).
- Results are parsed and displayed with the same selection UI as Story 4.3 (with a "Source: GnuDB" badge).
- If both sources fail, an "Unknown Disc" state is shown with the option to enter metadata manually.

---

## Epic 5 — Drive Capabilities & Offset Detection

> Before a rip can begin, the app must understand the drive's read characteristics and calibrate its sample offset.

---

### Story 5.1 — Detect Accurate Stream, C2, and cache support
- [ ] **Done**

**As a** developer,
**I want** a `DriveCapabilityDetector` that runs a one-time probe when a drive is first connected,
**so that** the ripping engine can select the correct algorithm for that drive.

**Acceptance criteria:**
- `GET CONFIGURATION (0x46)` is issued; Feature Code `0x0107` (CD Read) is parsed for the `AccurateStream` bit.
- Feature Code `0x0014` is checked for C2 pointer support.
- Cache detection is done via timing: read a sector, read the same sector again; if RTT < 5 ms on the second read, a cache is likely present. Probe cache size by increasing the distance of a "decoy" read until the second read is slow.
- All results are stored in `DriveCapabilities` and persisted by VID+PID in `SharedPreferences`.

---

### Story 5.2 — Look up drive read offset from the AccurateRip database
- [ ] **Done**

**As a** user,
**I want** the app to automatically find the correct read offset for my drive,
**so that** my rips are sample-accurate without manual configuration.

**Acceptance criteria:**
- On first use with a recognised drive model, the app queries the AccurateRip offset list at `http://www.accuraterip.com/driveoffsets.asp` (or a cached local copy).
- If the drive model string matches a known entry, the offset is stored and shown to the user as "Offset: +NNN samples (from AccurateRip database)".
- If not found, the user is prompted to either enter an offset manually or proceed with auto-calibration (Story 5.3).
- The stored offset is displayed in the Drive Info screen (Epic 2, Story 2.3).

---

### Story 5.3 — Auto-calibrate drive offset using AccurateRip
- [ ] **Done**

**As a** user,
**I want** the app to determine my drive's offset by ripping a well-known disc and comparing checksums,
**so that** I can calibrate without needing to look up values manually.

**Acceptance criteria:**
- A "Calibrate Offset" action is available in Drive Settings.
- The app rips one track from the inserted disc at offset 0, computes ARv1+ARv2 checksums, and queries AccurateRip.
- If the disc is in the AccurateRip DB, the app tries offsets in the range ±5 sectors (step 1 sample) until a checksum match is found.
- On match, the offset is saved and confirmed to the user.
- If no match is found after the scan range, a message directs the user to manual entry or to try a different reference disc.

---

## Epic 6 — Secure Ripping Engine

> The core extraction pipeline implementing EAC-style multi-pass majority vote, cache busting, and Test & Copy verification.

---

### Story 6.1 — Implement raw CD-DA sector reader (READ CD 0xBE)
- [ ] **Done**

**As a** developer,
**I want** a low-level `CdSectorReader` that issues `READ CD (0xBE)` for a given LBA range,
**so that** all higher-level extraction logic can request audio data without knowing USB details.

**Acceptance criteria:**
- Reads a configurable number of sectors per command (default: 1 sector at a time in secure mode).
- Returns raw `ByteArray` of 2,352 bytes per sector.
- Optionally returns 2,448 bytes per sector (2,352 audio + 96 subchannel bytes) when subchannel flag is set.
- Optionally returns C2 error pointer bytes when C2 flag is set.
- A `READ(10) (0x28)` fallback is attempted if `READ CD` returns an illegal request error.

---

### Story 6.2 — Implement the secure multi-pass majority vote algorithm
- [ ] **Done**

**As a** developer,
**I want** a `SecureSectorExtractor` that reads each sector up to 82 times and returns the majority result,
**so that** transient read errors are corrected before the data is written to disk.

**Acceptance criteria:**
- Implements the algorithm from `secure.md §3.1`: up to 5 batches of 16 reads per sector.
- After each batch, `findMajority()` is computed; if ≥ 8 of 16 reads agree, the result is returned with `SectorResult.SUCCESS`.
- If no majority is found after all batches, the most common result is returned with `SectorResult.SUSPICIOUS` and the MSF position is logged.
- Error recovery quality (1/3/5 batches) is a user-configurable setting.
- The extractor is injectable for unit testing with mock sector data.

---

### Story 6.3 — Implement cache-busting between re-reads
- [ ] **Done**

**As a** developer,
**I want** the extractor to issue a "decoy" read before each re-read of a cached drive,
**so that** we never compare a sector against its own cached copy.

**Acceptance criteria:**
- `DriveCapabilities.estimatedCacheSizeSectors > 0` enables cache-busting mode.
- Before each of the 16 reads in a batch, a decoy sector at `targetLba + 10_000` (clamped to disc end) is read and discarded.
- Cache-busting can be toggled off for drives where the cache probe returned no cache.
- A unit test verifies that the decoy read is issued the correct number of times.

---

### Story 6.4 — Implement Test & Copy (dual-pass CRC32 verification)
- [ ] **Done**

**As a** developer,
**I want** each track to be ripped twice and both runs' CRC32 values compared,
**so that** we can confirm the rip is stable without needing AccurateRip.

**Acceptance criteria:**
- Phase 1 (Test): rips the track, accumulates CRC32 over all sector bytes, discards the audio data (does not write to disk).
- Phase 2 (Copy): rips the track again, accumulates CRC32, writes audio to a temp file.
- Both CRC32 values are logged and compared.
- If they match → "Copy OK"; if not → "Copy NOT OK" with the first divergent sector's LBA logged.
- The Test phase can be skipped via a user setting (for speed).

---

### Story 6.5 — Apply sample offset correction to extracted audio
- [ ] **Done**

**As a** developer,
**I want** the extractor to shift the audio byte stream by the drive's read offset,
**so that** the output aligns with what the mastering engineer intended.

**Acceptance criteria:**
- Positive offset (+N): reads start N samples (= N×4 bytes) before the track's start LBA; the extra leading bytes are discarded. The track end is extended by N samples (or zero-filled if the drive can't overread).
- Negative offset (−N): reads start N samples after the track's start LBA. N leading silence bytes are prepended.
- Overread into lead-in/lead-out: attempted first; if the drive returns an error, the missing samples are silently zero-filled.
- An integration test rips a known-offset fixture and verifies the first and last sample values.

---

### Story 6.6 — Implement gap detection via Q subchannel
- [ ] **Done**

**As a** developer,
**I want** a `GapDetector` that reads Q subchannel data to find the exact pre-gap boundary for each track,
**so that** gapless albums are handled correctly.

**Acceptance criteria:**
- Uses `READ CD (0xBE)` with subchannel mode `0x02` (Q only) to scan the region between tracks.
- Binary search (Method A) locates the index 0 → index 1 boundary.
- Detected gap length is stored per track in `TrackEntry.preGapSectors`.
- The ripping pipeline respects a user-selectable gap strategy: append to previous track / prepend to next track / discard.

---

## Epic 7 — AccurateRip Verification

> After extraction, compare computed checksums against the AccurateRip database to confirm bit-perfect accuracy.

---

### Story 7.1 — Compute AccurateRip v1 and v2 checksums
- [ ] **Done**

**As a** developer,
**I want** `computeARv1(audioBytes, trackNum, totalTracks)` and `computeARv2(audioBytes, trackNum, totalTracks)`,
**so that** both legacy and current AccurateRip verification can be performed.

**Acceptance criteria:**
- ARv1 implements the position-weighted CRC from `secure.md §7.2`, including the 5-sector skip at the start of track 1 and end of the last track.
- ARv2 implements the 64-bit split accumulation from `secure.md §7.3`.
- Both functions are computed incrementally as sectors are received (not post-hoc).
- Unit tests use the published test vectors for a known reference disc.

---

### Story 7.2 — Fetch AccurateRip database response for the disc
- [ ] **Done**

**As a** developer,
**I want** an `AccurateRipClient` that fetches and parses the binary `.bin` response,
**so that** per-track confidence values are available for comparison.

**Acceptance criteria:**
- Constructs the URL from `AccurateRipDiscId` as per `secure.md §7.1` / Appendix C.
- Parses the binary response: track count header, then per-track `{confidence, trackCRC, offsetCRC}` records, iterated per pressing.
- Returns `List<AccurateRipPressing>` where each pressing has a list of `TrackVerification(confidence, crc)`.
- HTTP errors (404 = disc not in DB; 5xx = service unavailable) are handled gracefully.
- The raw response is cached to disk so it is not re-fetched if the app is restarted mid-rip.

---

### Story 7.3 — Compare computed checksums against database and display results
- [ ] **Done**

**As a** user,
**I want** to see per-track AccurateRip verification results after ripping,
**so that** I know with high confidence whether my rip is bit-perfect.

**Acceptance criteria:**
- For each track, the app searches all pressings for a matching CRC (v1 or v2).
- If a match is found, the result is shown as: `✅ Accurately ripped (confidence N) [ARv2]`.
- If no match is found, the result is: `⚠️ Not in AccurateRip database` (not treated as an error).
- If the disc is not in the database at all, a single banner message covers all tracks.
- Results are included in the rip log (Epic 8).

---

## Epic 8 — Log File Generation

> The app must produce an EAC-compatible rip log file that can be validated by external tools.

---

### Story 8.1 — Generate an EAC-format log file
- [ ] **Done**

**As a** user,
**I want** the app to produce a `.log` file in EAC format at the end of each rip,
**so that** I can verify and share my rip with the same tools used for EAC logs.

**Acceptance criteria:**
- Log header includes: app name + version, date/time, artist/album, drive model, read mode, settings (Accurate Stream, Cache Defeat, C2).
- TOC table is included (track, start, length, start sector, end sector).
- Per-track section includes: filename, pre-gap length, peak level, extraction speed, track quality %, Test CRC, Copy CRC, AccurateRip result, "Copy OK / NOT OK".
- Log is written to the output directory alongside the audio files.
- Log format follows `secure.md §10` exactly.

---

### Story 8.2 — Append SHA-256 checksum to the log file
- [ ] **Done**

**As a** user,
**I want** the log file to include a SHA-256 checksum of its content,
**so that** it can be verified as untampered by tools like CUETools.

**Acceptance criteria:**
- After all content is written to the log, the SHA-256 of the entire log text is computed.
- The line `==== Log checksum [<hex hash>] ====` is appended.
- A unit test verifies that re-reading the log and recomputing the hash produces a match.

---

## Epic 9 — UI Polish & Settings

> The remaining screens and settings that complete the user-facing product.

---

### Story 9.1 — Implement the main screen idle / active state machine
- [ ] **Done**

**As a** user,
**I want** the main screen to clearly show the current app state at a glance,
**so that** I always know what the app is doing or waiting for.

**Acceptance criteria:**
- States and their UI representation:
  - `NoDrive` → icon + "Connect a USB CD drive"
  - `DriveConnected / NoDisc` → drive info + "Insert a disc" + Eject button
  - `TrayOpen` → "Tray open" + Close Tray button
  - `ReadingToc` → spinner + "Reading disc…"
  - `TocReady` → track list + "Rip" FAB
  - `LookingUpMetadata` → track list + shimmer loading on track names
  - `MetadataReady` → full track list with names + "Rip" FAB
  - `Ripping` → per-track progress bars + overall progress + "Cancel" button
  - `RipComplete` → results summary + AccurateRip badges + "View Log" button
- Transitions use Material Shared Axis animations (per `design_spec.md §3.4`).

---

### Story 9.2 — Rip progress screen with per-track status
- [ ] **Done**

**As a** user,
**I want** to see real-time progress for each track as it is being ripped,
**so that** I know how far along the rip is and whether any errors are being encountered.

**Acceptance criteria:**
- A `LinearProgressIndicator` per track shows sector-level progress (sectors read / total sectors).
- An overall progress bar spans the full disc.
- Each track row shows: track number, title (if known), duration, current status (Waiting / Ripping / Done / Error).
- Suspicious sector count is shown in real time next to each track as it accumulates.
- Estimated time remaining is displayed based on current throughput.
- The rip runs in a `ForegroundService` with a persistent notification showing overall progress.

---

### Story 9.3 — Settings screen
- [ ] **Done**

**As a** user,
**I want** a settings screen where I can configure ripping behaviour,
**so that** I can balance accuracy and speed for my use case.

**Acceptance criteria:**
- Settings include:
  - Error recovery quality: Low (1 batch) / Medium (3 batches) / High (5 batches)
  - Use C2 error pointers: On / Off
  - Gap handling strategy: Append / Prepend / Discard
  - Skip Test phase (speed mode): On / Off
  - Manual drive read offset: numeric input (overrides auto-detected value)
  - Output format: FLAC / WAV
  - Output directory: file picker
- Settings use M3 `PreferenceItem` / `PreferenceSwitch` components (per `design_spec.md §3.2`).
- All settings are persisted via `DataStore`.

---

### Story 9.4 — Post-rip results and log viewer
- [ ] **Done**

**As a** user,
**I want** to view a summary of my rip results and read the full log file in-app,
**so that** I can confirm everything went well before ejecting the disc.

**Acceptance criteria:**
- Summary screen shows: album art, title, artist, track count, total rip time, overall AccurateRip confidence, any suspicious positions.
- A "View Log" button expands a monospaced scrollable log view.
- A "Share Log" button lets the user export the `.log` file via Android's share sheet.
- A "Rip Another" button returns to the idle state and polls for a new disc.
