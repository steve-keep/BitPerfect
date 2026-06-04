# RipManager and Drive State Architecture

This document outlines the complex interactions between device state management, drive polling, and the secure audio extraction process managed by `RipManager.kt`.

## State Machines

The ripping system operates on two distinct but interrelated state machines:

1.  **Hardware State (`DriveStatus`)**: Managed by `UsbDriveDetector` via `DeviceStateManager`. It polls the USB transport to maintain the physical state of the drive.
2.  **Extraction State (`RipStatus`)**: Managed by `RipManager` (tracking individual `TrackRipState`). It operates on the assumption that the drive is `DiscReady` and aborts if the hardware state changes unexpectedly.

### Combined State Diagram

```mermaid
stateDiagram-v2
    %% Drive Status Lifecycle
    state "Hardware State (DriveStatus)" as HW {
        [*] --> Connecting
        Connecting --> Empty : Drive Ready, No Disc
        Connecting --> Error : USB Lost / Timeout
        Empty --> SpinningUp : Disc Inserted
        Empty --> Open : Tray Ejected
        Open --> Empty : Tray Closed
        SpinningUp --> DetectingDisc : Mechanical Spin Complete
        DetectingDisc --> DiscReady : TOC Read Successful
        DetectingDisc --> Error : TOC Read Failed
        DiscReady --> Ejecting : User/System Eject
        Ejecting --> Open : Tray Ejected

        DiscReady --> Empty : Disc Removed (Unexpected)
    }

    %% Rip Status Lifecycle
    state "Track Extraction State (RipStatus)" as Extraction {
        [*] --> IDLE : Queued
        IDLE --> RIPPING : Start Track
        RIPPING --> VERIFYING : Sector Loop Complete
        VERIFYING --> SUCCESS : AccurateRip Match (V1/V2)
        VERIFYING --> WARNING : AccurateRip Mismatch
        VERIFYING --> UNVERIFIED : Not in Database

        RIPPING --> ERROR : Read Limit Exceeded / Write Fail
        RIPPING --> CANCELLED : User Cancelled

        %% Hardware interlock
        RIPPING --> ERROR : DriveStatus != DiscReady
    }

    note right of HW
        Polled via TEST UNIT READY.
        Paused automatically during
        UsbReadSession to prevent
        transaction collision.
    end note
```

## Sequence Diagrams

### Happy Path: Successful Track Extraction

This sequence illustrates a flawless track extraction where all sectors are read correctly, overlaps match, and checksums are verified successfully.

```mermaid
sequenceDiagram
    participant UI as ViewModel / UI
    participant DSM as DeviceStateManager
    participant RM as RipManager
    participant URS as UsbReadSession
    participant OV as OverlapVerifier
    participant FLAC as FlacEncoder
    participant ACC as ChecksumAccumulator
    participant ARV as AccurateRipVerifier

    UI->>RM: queueTrack(1)
    UI->>RM: startRipping(session)

    RM->>RM: updateTrackState(RIPPING)

    loop Every Chunk (e.g. 16 sectors)
        RM->>URS: readSectors(lba, 16)
        URS->>DSM: pausePolling()
        URS-->>RM: pcmData (Success)
        URS->>DSM: resumePolling()

        RM->>OV: extractOverlapHead/Tail
        alt If not first chunk
            RM->>OV: verifyOverlap(prevTail, currentHead)
            OV-->>RM: Match (true)
            RM->>OV: commitVerifiedAudio()
            OV-->>RM: committedPcm
        end

        RM->>FLAC: encode(committedPcm)
        RM->>ACC: accumulate(committedPcm)
        RM->>RM: updateTrackState(progress)
    end

    RM->>FLAC: stop()
    RM->>ACC: finalise()
    ACC-->>RM: (finalChecksumV1, finalChecksumV2)

    RM->>RM: updateTrackState(VERIFYING)
    RM->>ARV: Check expected hashes vs computed
    ARV-->>RM: Match found (V2)

    RM->>RM: updateTrackState(SUCCESS)
    RM-->>UI: Ripping Complete
```

### Sad Path: Paranoia Escalation and Hardware Failure

This sequence illustrates what happens when the drive returns short reads, overlaps mismatch, and eventually, the drive is unexpectedly disconnected or cannot recover.

```mermaid
sequenceDiagram
    participant RM as RipManager
    participant DSM as DeviceStateManager
    participant URS as UsbReadSession
    participant OV as OverlapVerifier
    participant RC as RecoveryCoordinator
    participant CE as RipConfidenceEvaluator

    RM->>URS: readSectors(lba, 16)
    URS-->>RM: pcmData (Success)

    RM->>OV: verifyOverlap(prevTail, currentHead)
    OV-->>RM: Mismatch (false)

    note over RM,RC: Overlap Mismatch Detected
    RM->>RC: recover(previousChunk, failedChunk, readLambda)

    loop Reread Attempts (Max 6)
        RC->>URS: readSectors (via lambda)
        URS-->>RC: newPcmData
        RC->>OV: verifyOverlap(...)
        alt Successful Recovery
            OV-->>RC: Match (true)
            RC-->>RM: Recovered(metadataHistory)
        else Escalation
            OV-->>RC: Mismatch (false)
            RC->>RC: Escalate strategy (Full Chunk Reread)
        end
    end

    alt Recovery Failed Ultimately
        RC-->>RM: Failed(metadataHistory)
        RM->>CE: evaluateChunkConfidence(rereads, false)
        CE-->>RM: Confidence Downgraded (LOW/DAMAGED)
        RM->>RM: updateTrackState(suspiciousRegions++)
    end

    note over RM,URS: Fatal Transport Failure

    RM->>URS: readSectors(lba+n, 16)

    loop 3 Retries (UsbReadSession)
        URS->>DSM: Check driveStatus == DiscReady
        DSM-->>URS: Empty / Error (Drive Removed)
        URS-->>RM: null
    end

    RM->>RM: Drive removed exception thrown
    RM->>RM: updateTrackState(ERROR)
    RM->>RM: cancel()
    RM->>RM: Cleanup Partial Files
```

## Component Complexity & Traceability

*   **`UsbDriveDetector`**: Highly complex background daemon. Maintains continuous connection with the SCSI layer using low-level CBW (Command Block Wrapper) testing (TEST UNIT READY).
*   **`DeviceStateManager`**: Singleton registry for the hardware state. Vital for orchestrating the "polling pause" during `UsbReadSession` to prevent command interleaving that crashes the USB bridge.
*   **`RipManager`**: The monolithic orchestrator. It manages:
    *   File I/O (SAF / Temp Files)
    *   Metadata embedding (FlacEncoder)
    *   The Paranoia/Recovery pipeline (`OverlapVerifier`, `RecoveryCoordinator`, `AlignmentValidator`)
    *   AccurateRip state machine verification
    *   Forensic logging (`DefaultForensicRipLogger`)
*   **Paranoia Subsystem**: `RecoveryCoordinator` encapsulates the retry logic (`RereadEngine`) when overlaps fail, determining if a drive is skipping or shifting samples (Read Drift). It feeds this data into the `RipConfidenceEvaluator` to score the rip (HIGH, MEDIUM, LOW, DAMAGED).
