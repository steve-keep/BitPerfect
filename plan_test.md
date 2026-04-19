# Plan

I need to implement Story 5.1

1. **Extract `DriveCapabilityDetector` logic into its own class or method**
   - The user story says `DriveCapabilityDetector` should be a separate class. But currently, `detectCapabilities` is inside `RippingEngine.kt`.
   - Update `RippingEngine` to use `DriveCapabilityDetector` or move the method.
2. **Update `GET CONFIGURATION (0x46)` implementation for capability detection**
   - Issue `GET CONFIGURATION` (opcode `0x46`).
   - Feature Code `0x0107` (CD Read) should be parsed for `AccurateStream` bit. (Specifically, feature descriptor data).
   - Feature Code `0x0014` (Random Readable?) or maybe just error pointers. Actually MMC spec says: C2 pointer support.
   - Actually currently `detectCapabilities` uses `MODE SENSE (0x5A)`. I need to change it to use `GET CONFIGURATION (0x46)`.
3. **Implement Cache detection via timing**
   - Read a sector, read it again. If RTT < 5 ms on the second read, a cache is likely present.
   - Probe cache size by increasing distance of a decoy read.
   - In `VirtualScsiDriver.kt`, simulate this behavior for the virtual drive to support the test.
4. **Update `VirtualScsiDriver.kt`**
   - Add support for `GET CONFIGURATION (0x46)`.
   - Add timing/cache simulation logic to `READ CD` so that `DriveCapabilityDetector` can correctly probe cache size.
