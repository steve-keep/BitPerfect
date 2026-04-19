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
