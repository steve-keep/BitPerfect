The plan is to implement Story 5.1: Detect Accurate Stream, C2, and cache support.

1. **Extract capability detection logic into `DriveCapabilityDetector`:**
   - Create a new class `DriveCapabilityDetector`.
   - Move the capability detection logic from `RippingEngine.detectCapabilities` to `DriveCapabilityDetector.detect`.
   - Modify `RippingEngine` to instantiate and use `DriveCapabilityDetector`.

2. **Implement `GET CONFIGURATION (0x46)`:**
   - In `DriveCapabilityDetector`, construct and issue the `GET CONFIGURATION` command.
   - Parse the response for Feature Code `0x0107` (CD Read) and check bit 1 of byte 4 of the feature descriptor for `AccurateStream` support.
   - Parse the response for Feature Code `0x0014` and check bit 0 of byte 4 of the feature descriptor for C2 pointers support.

3. **Implement Cache detection via timing:**
   - In `DriveCapabilityDetector`, implement a timing-based cache probe:
     - Read sector 0 twice. If the RTT of the second read is < 5 ms, a cache is likely present.
     - Probe cache size by increasing the distance of a "decoy" read (e.g., read sector 0, read sector N, read sector 0 again) until the second read of sector 0 is slow (> 5ms). The distance `N` roughly estimates the cache size.

4. **Update `VirtualScsiDriver` to support detection:**
   - Implement `handleGetConfiguration` (opcode `0x46`) to return mock capability descriptors containing `0x0107` and `0x0014`.
   - Update `handleReadCd` (opcode `0xBE`) to include an artificial delay on the first read of a sector, but return immediately if the sector was read recently (simulating a cache). This will allow `DriveCapabilityDetector`'s timing check to pass in tests.

5. **Create Unit and Integration Tests:**
   - Write unit tests in `DriveCapabilityDetectorTest.kt` using a mock `IScsiDriver` to verify `GET CONFIGURATION` parsing and cache timing logic.
   - Run the existing `CapabilityDetectionRobolectricTest` to ensure it passes.

6. **Pre-commit verification:**
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
