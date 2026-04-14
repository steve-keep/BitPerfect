# PRD: BitPerfect — Android Secure CD Ripper

## 1. Project Overview
**BitPerfect** is an open-source, forensic-grade audio CD extraction utility for Android. Unlike standard "burst" rippers, BitPerfect focuses on data integrity, hardware-level diagnostics, and bit-perfect verification using the AccurateRip database. 

The goal is to provide a mobile alternative to desktop tools like Exact Audio Copy (EAC) or Whipper.

* **Project Focus:** Secure Ripping, Hardware Diagnostics, AccurateRip Verification.
* **License:** [MIT License](https://opensource.org/licenses/MIT)
* **Platform:** Android (7.0+ recommended)
* **Distribution:** GitHub (Installable APK via GitHub Actions/Releases)

---

## 2. Target User
* **Audiophiles:** Users wanting to digitize CDs without a desktop computer.
* **Archivists:** Users requiring a verifiable log proving the integrity of the digital copy.
* **Technical Users:** Users who want to understand their hardware limitations (C2 support, cache behavior).

---

## 3. Functional Requirements

### 3.1. Hardware Interface (Driver Layer)
* **USB Host API:** Access external USB optical drives without root using the Android USB Host API.
* **SCSI/MMC Command Set:** Implement raw Multi-Media Commands (MMC) over USB bulk transfers to communicate directly with the drive.
* **Diagnostics Dashboard:**
    * **C2 Pointer Support:** Detect if the drive reports physical read errors.
    * **Cache Detection:** Determine if the drive caches audio data (critical for secure mode logic).
    * **Read Offset:** Allow manual or automatic calibration of the drive's sample offset.
    * **Inquiry Data:** Display Vendor, Product, and Revision.

### 3.2. Secure Ripping Engine
* **Two-Pass Verification:** Read every sector at least twice. Compare CRC32 checksums of both passes.
* **Re-read Logic:** If a mismatch occurs, perform multiple re-reads (up to 80+ times) to find a statistical majority.
* **Cache Flushing:** If the drive is detected to have a cache, the engine must "flush" the cache by reading a sector from a different area of the disc between passes.
* **C2 Error Handling:** If C2 pointers are supported, the engine should prioritize these hardware flags to identify damaged sectors instantly.

### 3.3. Accuracy Verification
* **AccurateRip Integration:** Calculate the AccurateRip-compatible CRC for each track.
* **Database Lookup:** Query the AccurateRip database (over HTTP) to compare local CRCs against other users' submissions.
* **Confidence Scores:** Display a "Confidence" level (e.g., "Accurate: Match found with 12 other users").

### 3.4. Metadata & Tags
* **MusicBrainz Integration:** Fetch Artist, Album, Year, and Track titles using the MusicBrainz DiscID.
* **Metadata Embedding:** Automatically tag the output files with Vorbis comments (for FLAC).

---

## 4. Technical Specifications

| Component | Technology |
| :--- | :--- |
| **User Interface** | Jetpack Compose (Kotlin) |
| **Hardware Driver** | C++ (NDK) using **libusb** or Android **UsbDeviceConnection** |
| **Audio Processing** | PCM extraction to FLAC (via **libflac** or FFmpeg mobile) |
| **Networking** | Ktor or Retrofit (for MusicBrainz/AccurateRip API) |
| **Concurrency** | Kotlin Coroutines with Foreground Service (to prevent OS termination) |

---

## 5. Output & Archival Requirements

### 5.1. File Output
* **Format:** Lossless FLAC.
* **Structure:** `/Music/BitPerfect/[Artist]/[Year] - [Album]/[Track] - [Title].flac`

### 5.2. Rip Log (`rip_log.txt`)
A detailed log must be generated for every rip session, stored alongside the audio files.
* **Header:** App version, Date, Drive model, Drive capabilities.
* **Per-Track Details:**
    * Ripping status (Accurate, Secure, or Inaccurate).
    * Number of re-reads performed.
    * CRC32 and AccurateRip checksums.
* **Footer:** Summary of errors or successes.

---

## 6. UI/UX Requirements
* **Diagnostic View:** A pre-rip screen showing drive health and capabilities.
* **Active Terminal:** A scrollable log view showing real-time SCSI command feedback during the rip.
* **Progress Indicators:** Visual feedback on sector-by-sector progress and "Confidence" growth.
* **No Playback:** Explicitly excludes a music player to keep the app focused and lightweight.

---

## 7. Roadmap

### Phase 1: Communication
* Establish USB OTG connection.
* Implement SCSI `INQUIRY` and `MODE SENSE` commands for diagnostics.

### Phase 2: Extraction
* Implement `READ CD` (0xBE) command.
* Buffer raw PCM data and implement a basic "Burst" rip to FLAC.

### Phase 3: Integrity
* Build the Secure Engine (Double-pass + Cache flushing).
* Implement CRC32 calculations.

### Phase 4: Metadata & Verification
* Integrate MusicBrainz and AccurateRip APIs.
* Generate the final `rip_log.txt`.

### Phase 5: Distribution
* Configure GitHub Actions for CI/CD to generate APKs automatically.
