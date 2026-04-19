The plan is to implement Story 5.2: Look up drive read offset from the AccurateRip database.

1. **Create `DriveOffsetService` to fetch and parse drive offsets:**
   - Create a new file `core/src/main/kotlin/com/bitperfect/core/engine/DriveOffsetService.kt`.
   - Implement `fetchDriveOffsets()` which uses Ktor `HttpClient` to download `http://www.accuraterip.com/driveoffsets.htm`.
   - Parse the HTML table using a regex or string matching to extract the CD Drive name and its Correction Offset.
   - Store the result in a map and cache it locally in memory.
   - Implement `findOffsetForDrive(vendor: String, product: String): Int?` which normalizes the vendor and product string (matching the format in the AccurateRip list) and looks up the offset. The string mappings are explicitly documented in the HTML text itself: "JLMS drives are listed as Lite-ON, HL-DT-ST as LG Electronics & Matshita as Panasonic", which was verified using `grep -A 2 -i "JLMS" offsets.html` on the raw HTML file.

2. **Verify `DriveOffsetService` implementation:**
   - Use `./gradlew lint --quiet` to verify the code compiles and passes static analysis without errors.

3. **Integrate Offset Lookup during Capability Detection:**
   - Modify `core/src/main/kotlin/com/bitperfect/core/engine/RippingEngine.kt` to instantiate `DriveOffsetService` and call `DriveOffsetService.findOffsetForDrive()` inside `detectCapabilities()`.
   - Update `core/src/main/kotlin/com/bitperfect/core/engine/RippingEngine.kt`'s `DriveCapabilities` data class to add a boolean `offsetFromAccurateRip: Boolean = false`.
   - Modify `core/src/main/kotlin/com/bitperfect/core/utils/SettingsManager.kt` to persist the new `offsetFromAccurateRip` field.

4. **Verify Capability Integration:**
   - Use `./gradlew lint --quiet` to verify the updated model and integration compile without errors.

5. **Update UI to show the offset:**
   - Modify `app/src/main/kotlin/com/bitperfect/app/ui/Components.kt`'s `DiagnosticDashboard` to display `"Read Offset: +${driveCapabilities.readOffset} samples (from AccurateRip database)"` if `offsetFromAccurateRip` is true, otherwise fallback to the existing display `"Read Offset: ${driveCapabilities.readOffset}"`.

6. **Verify UI update:**
   - Use `./gradlew lint --quiet` to verify the UI code compiles without errors.

7. **Create Unit Test for `DriveOffsetService`:**
   - Create `core/src/test/kotlin/com/bitperfect/core/engine/DriveOffsetServiceTest.kt` to test parsing the HTML offset list with mock data using Ktor MockEngine.

8. **Update `CapabilityDetectionRobolectricTest` Integration Test:**
   - Modify `app/src/test/kotlin/com/bitperfect/app/CapabilityDetectionRobolectricTest.kt` to explicitly mock `SharedPreferences` to simulate `offsetFromAccurateRip = true`, and assert that the text `(from AccurateRip database)` appears in the UI.

9. **Run test suite:**
   - Run `./gradlew test` to ensure all tests pass and there are no regressions.

10. **Pre-commit verification:**
    - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
