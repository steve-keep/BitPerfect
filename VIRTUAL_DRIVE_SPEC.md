# Virtual Drive Simulation Specification

This document specifies the implementation of a Virtual Drive simulation for the BitPerfect Android application. This feature allows for UI flow testing and development without requiring a physical USB optical drive.

## 1. Architectural Changes

### 1.1. Driver Abstraction (`IScsiDriver`)
The application was refactored to use an interface for SCSI command execution, decoupling the business logic from the native JNI implementation.

```kotlin
interface IScsiDriver {
    fun getDriverVersion(): String
    fun executeScsiCommand(
        fd: Int,
        command: ByteArray,
        expectedResponseLength: Int,
        endpointIn: Int = 0x81,
        endpointOut: Int = 0x01,
        timeout: Int = 5000
    ): ByteArray?
}
```

### 1.2. Virtual Driver Implementation (`VirtualScsiDriver`)
A virtual implementation of `IScsiDriver` was created to simulate hardware responses based on a selected "Test CD".

**Key Simulated Commands:**
- `0x12 (INQUIRY)`: Returns mock vendor, product, and revision strings.
- `0x5A (MODE SENSE 10)`: Simulates drive capabilities, such as C2 error pointer support.
- `0x43 (READ TOC)`: Returns the track structure (first track, last track, and offsets).
- `0xBE (READ CD)`: Generates deterministic dummy PCM data for a given LBA.

### 1.3. Driver-Agnostic Ripping Engine
The `RippingEngine` was updated to accept an `IScsiDriver` instance as a parameter for its ripping methods (`startBurstRip`, `startSecureRip`, `fullRip`), allowing it to switch between physical and virtual drivers at runtime.

## 2. Data Models

### 2.1. `BitPerfectDrive`
A sealed class used to represent both physical USB devices and virtual simulated drives in the UI.

```kotlin
sealed class BitPerfectDrive {
    abstract val name: String
    abstract val manufacturer: String?
    abstract val identifier: String

    data class Physical(val device: UsbDevice) : BitPerfectDrive()
    data class Virtual(val id: Int, val vendor: String, val product: String) : BitPerfectDrive()
}
```

### 2.2. `TestCd`
A data structure representing mock CD metadata (Artist, Album, Tracks) used by the virtual driver.

## 3. Persistence (`SettingsManager`)
The simulation state is persisted using `SharedPreferences`.

- `isVirtualDriveEnabled`: Boolean toggle to show/hide the virtual drive in the device list.
- `selectedTestCdIndex`: Integer index of the currently active mock CD.

## 4. User Interface

### 4.1. Settings Screen
A new screen was added to toggle the virtual drive and select from five pre-defined test CDs:
1. Pink Floyd - The Dark Side of the Moon
2. Michael Jackson - Thriller
3. Fleetwood Mac - Rumours
4. Nirvana - Nevermind
5. Daft Punk - Random Access Memories

### 4.2. Device List & Diagnostics
The main device list was updated to display the Virtual Drive when enabled. The Diagnostic Dashboard and Ripping flows operate identically for both drive types, with the virtual driver bypassing real USB permissions and interface claiming.

## 5. Implementation Summary
- **Module `:core`**: Contains `SettingsManager`, `BitPerfectDrive`, `VirtualScsiDriver`, and the refactored `RippingEngine`.
- **Module `:app`**: Contains the `SettingsScreen` UI and updated `MainActivity` navigation logic.
- **Module `:driver`**: Remains focused on native JNI communication, providing the `ScsiDriver` implementation of `IScsiDriver`.
