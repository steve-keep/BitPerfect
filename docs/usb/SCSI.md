# SCSI Commands Used in BitPerfect

BitPerfect communicates with physical USB optical drives using SCSI commands transmitted over the USB Bulk-Only Transport (BOT) protocol. This document details the exact SCSI Command Descriptor Blocks (CDBs) currently implemented in the codebase.

## TEST UNIT READY (Opcode: `0x00`)
Used to check if the drive is ready to accept commands (e.g., disc inserted, tray closed, drive spun up).
- **Location**: `UsbDriveDetector.kt`
- **Command Block (6 bytes)**:
  - `Byte 0`: `0x00` (Opcode: TEST UNIT READY)
  - `Byte 1`: `0x00`
  - `Byte 2`: `0x00`
  - `Byte 3`: `0x00`
  - `Byte 4`: `0x00`
  - `Byte 5`: `0x00`

## REQUEST SENSE (Opcode: `0x03`)
Used immediately after a command fails to retrieve extended error information (Sense Key, Additional Sense Code, etc.).
- **Location**: `UsbDriveDetector.kt`
- **Command Block (6 bytes)**:
  - `Byte 0`: `0x03` (Opcode: REQUEST SENSE)
  - `Byte 1`: `0x00`
  - `Byte 2`: `0x00`
  - `Byte 3`: `0x00`
  - `Byte 4`: `0x12` (Allocation length: 18 bytes)
  - `Byte 5`: `0x00`

## INQUIRY (Opcode: `0x12`)
Used to retrieve basic device information such as the Vendor ID, Product ID, and firmware revision.
- **Location**: `ScsiInquiryCommand.kt`
- **Command Block (6 bytes)**:
  - `Byte 0`: `0x12` (Opcode: INQUIRY)
  - `Byte 1`: `0x00`
  - `Byte 2`: `0x00`
  - `Byte 3`: `0x00`
  - `Byte 4`: `0x24` (Allocation length: 36 bytes)
  - `Byte 5`: `0x00`

## START STOP UNIT (Opcode: `0x1B`)
Used by the app to eject the disc tray.
- **Location**: `EjectCommand.kt`
- **Command Block (6 bytes)**:
  - `Byte 0`: `0x1B` (Opcode: START STOP UNIT)
  - `Byte 1`: `0x00` (Immed = 0)
  - `Byte 2`: `0x00` (Reserved)
  - `Byte 3`: `0x00` (Reserved)
  - `Byte 4`: `0x02` (LoEj = 1 for Eject, Start = 0 for Stop)
  - `Byte 5`: `0x00` (Control)

## READ TOC/PMA/ATIP (Opcode: `0x43`)
Used to retrieve the Table of Contents (TOC) from the inserted CD. BitPerfect implements three specific variations of this command to retrieve different details.
- **Location**: `ReadTocCommand.kt`

### Variation 1: MSF=0, Format=0x00 (LBA Format)
- **Command Block (10 bytes)**:
  - `Byte 0`: `0x43` (Opcode)
  - `Byte 1`: `0x00` (MSF bit = 0, meaning LBA format)
  - `Byte 2`: `0x00` (Format `0b0000`)
  - `Bytes 3-5`: `0x00` (Reserved)
  - `Byte 6`: `0x00` (Track/Session Number = 0)
  - `Bytes 7-8`: Allocation Length (MSB, LSB)
  - `Byte 9`: `0x00`

### Variation 2: MSF=1, Format=0x02 (Full TOC)
- **Command Block (10 bytes)**:
  - `Byte 0`: `0x43` (Opcode)
  - `Byte 1`: `0x02` (MSF bit set = 1)
  - `Byte 2`: `0x02` (Format `0b0010`)
  - `Bytes 3-5`: `0x00` (Reserved)
  - `Byte 6`: `0x00` (Session Number = 0)
  - `Bytes 7-8`: Allocation Length (MSB, LSB)
  - `Byte 9`: `0x00`

### Variation 3: MSF=1, Format=0x00 (Standard TOC)
- **Command Block (10 bytes)**:
  - `Byte 0`: `0x43` (Opcode)
  - `Byte 1`: `0x02` (MSF bit set = 1)
  - `Byte 2`: `0x00` (Format `0b0000`)
  - `Bytes 3-5`: `0x00` (Reserved)
  - `Byte 6`: `0x01` (Session Number = 1)
  - `Bytes 7-8`: Allocation Length (MSB, LSB)
  - `Byte 9`: `0x00`

## READ CD (Opcode: `0xBE`)
Used to extract raw audio sectors from the CD.
- **Location**: `ReadCdCommand.kt`
- **Command Block (12 bytes)**:
  - `Byte 0`: `0xBE` (Opcode: READ CD)
  - `Byte 1`: `0x00` (Expected sector type = 0/any)
  - `Bytes 2-5`: Starting LBA (big-endian)
  - `Bytes 6-8`: Transfer length in sectors (big-endian)
  - `Byte 9`: `0x10` (Sync/Header flags indicating only user data/audio)
  - `Byte 10`: `0x00` (Sub-channel data format)
  - `Byte 11`: `0x00` (Control)
