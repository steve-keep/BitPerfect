# Memory/Learnings
* To intercept system volume hardware button presses without fighting Android's `MediaSession` and `AudioManager`, rely on observing `android.provider.Settings.System.CONTENT_URI` using a `ContentObserver`, mapping the resulting `STREAM_MUSIC` to the application's internal gain flow.
* Removing `onKeyDown` and `onKeyUp` from `MainActivity` entirely ensures the hardware volume buttons function optimally for system volume control while the observer accurately reflects these changes.
# BitPerfect Agent Instructions

This project follows a specific design language described in [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

## Design Constraints
- **No-Line Rule**: Do not use 1px solid borders for sectioning. Use background tonal shifts.
- **Tonal Layering**: Achieve depth through background colors (Surface Container levels) rather than shadows.
- **Editorial Typography**: Use Manrope (Headlines) and Inter (Body) hierarchy.
- **Logo Context**: The app logo must be displayed on a dark background in headers.

## UI Components
Reusable components are defined in `app/src/main/kotlin/com/bitperfect/app/ui/Components.kt`. Always prefer these over raw Compose components to maintain design consistency.

## Tech Stack
- Jetpack Compose for UI.
- Multi-module architecture: `:app` (UI), `:core` (Logic), `:driver` (NDK).

## Testing Requirements for Agents

**CRITICAL INSTRUCTION**: You must *always* write unit tests for any new code or logic modifications you make. The project has a goal of maintaining at least 70% unit test coverage. Even though this coverage requirement is not currently strictly enforced by CI to fail builds, it is a strict requirement for *you* (the agent). Whenever you create a new feature or fix a bug, your plan and execution must include creating or updating tests to cover the changes.

## Pre-PR Checklist

Before opening or pushing to a pull request, you **must** run the full test suite locally and confirm it passes.

### Run all tests (no device required)

```bash
# Runs unit tests + Robolectric integration tests on the JVM
./gradlew test

# Run only the Robolectric integration tests
./gradlew test --tests "*RobolectricTest*"

# Run only unit tests
./gradlew test --tests "*.unit.*"
```

All commands must complete with `BUILD SUCCESSFUL` and **zero test failures** before the PR is submitted.

### Test types in this project

| Type | Location | Runner | Requires device? |
|------|----------|--------|-----------------|
| Unit tests | `src/test/` | JUnit 4 / JUnit 5 | No |
| Robolectric integration tests | `src/test/*RobolectricTest.kt` | Robolectric 4.x | No |

The Robolectric tests were migrated from the previous `androidTest` (instrumented) suite.
Specifications live in `docs/integration-tests/INTEGRATION_TESTS_CUCUMBER.md`.

## Documentation Reference

The `docs/` folder contains essential architectural and technical reference documents that you should consult when working on specific domains.

- **[AccurateRip — Complete Technical Reference](docs/ripping/AccurateRip.md)**: Details the AccurateRip verification pipeline, Disc ID calculation, database fetching, V1/V2 binary parsing, checksum accumulation (with details on exclusion windows and drive offsets), and candidate verification. Also covers drive offset calibration and known issues/gap analysis. Refer to this when working on `AccurateRipService`, `ChecksumAccumulator`, or the verification logic.
- **[RipManager Architecture](docs/ripping/RipManager.md)**: Documents the core execution flow of the monolithic `RipManager`. It details the per-track sector read loop, overlap verification and mismatch recovery, FLAC metadata generation, transport failure handling, post-session analysis, and SAF operations. Consult this before making any structural changes to the CD extraction pipeline.
- **[SCSI/MMC Commands — Technical Reference](docs/usb/SCSI.md)**: Explains the USB Mass Storage — Bulk-Only Transport (BOT) protocol and the SCSI MMC-6/SPC-4 commands implemented in the app (e.g., INQUIRY, TEST UNIT READY, REQUEST SENSE, READ TOC, READ CD). Includes CDB layouts, state machine transitions, and unimplemented gaps (like tray locking or C2 error reading). Read this when dealing with `UsbDriveDetector`, `UsbReadSession`, or any low-level optical drive command logic.
