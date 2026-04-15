# Design Specification & Technology Implementation

This document provides a comprehensive analysis of the design language and technical implementation of the analyzed application, serving as a blueprint for achieving a similar high-quality User Interface (UI) and User Experience (UX).

## 1. Design Principles

The application adheres to **Material Design 3 (Material You)** principles, focusing on personalization, adaptability, and expressive motion.

### Core Visual Traits:
- **Dynamic Color (Monet):** The UI color scheme is derived from the user's system wallpaper or a selected seed color, ensuring the app feels integrated with the OS.
- **Organic Shapes:** Heavy use of rounded corners (ExtraLarge/Small shapes) for cards, buttons, and sheets.
- **Clarity & Focus:** Clean layouts with significant whitespace, using tonal variations instead of heavy borders to separate content.
- **Adaptive Layouts:** Seamless transition between mobile (Compact), tablet (Medium), and desktop (Expanded) orientations using Navigation Rails and Drawers.

---

## 2. Technology Stack

- **UI Framework:** Jetpack Compose (100% Kotlin)
- **Design System:** Material Design 3 (M3)
- **Dynamic Coloring:** Custom implementation based on the "Monet" engine (Tonal Palettes).
- **Navigation:** Jetpack Compose Navigation with custom transition animations.
- **Dependency Injection:** Koin (for ViewModels and services).
- **Image Loading:** Coil (with custom implementations for previews and dynamic color icons).
- **Motion:** Custom Material Shared Axis transitions and Path-based easings.

---

## 3. Implementation Details

### 3.1. Theme & Color System
The app uses a sophisticated color generation system that extends the standard M3 capabilities.

#### Tonal Palettes
Colors are not hardcoded. Instead, they are generated as **Tonal Palettes**. A seed color is used to create five key palettes:
1. **Accent1 (Primary)**
2. **Accent2 (Secondary)**
3. **Accent3 (Tertiary)**
4. **Neutral1 (Background/Surface)**
5. **Neutral2 (Surface Variant/Outline)**

#### Technical Snippet: Dynamic Color Scheme
```kotlin
@Composable
fun dynamicColorScheme(isLight: Boolean): ColorScheme {
    return if (isLight) {
        lightColorScheme(
            primary = 40.a1, // Tone 40 of Accent1
            primaryContainer = 90.a1,
            surface = 98.n1, // Tone 98 of Neutral1
            // ... other mappings
        )
    } else {
        darkColorScheme(
            primary = 80.a1, // Tone 80 of Accent1
            surface = 6.n1,  // Tone 6 of Neutral1
            // ...
        )
    }
}
```

#### Color Harmonization
Custom colors (like error reds or custom category labels) are "harmonized" with the primary theme color to prevent visual clashing.
- **Implementation:** Uses `MaterialColors.harmonize(colorToHarmonize, primaryColor)`.

---

### 3.2. UI Components

#### Reusable Preference Items
The settings screens are built using modular components that handle state and user interaction consistently.
- **`PreferenceItem`:** A standard row with an icon, title, and description. Uses `combinedClickable` for long-press support.
- **`PreferenceSwitch`:** Integrates a M3 `Switch` with a preference row.
- **`PreferencesHintCard`:** Uses `primaryFixed` or `secondaryFixed` colors to create eye-catching call-to-action cards.

#### Video/Content Cards (`VideoCardV2`)
- **Structure:** `ElevatedCard` containing an `AsyncImage` with a 16:9 aspect ratio.
- **Visuals:** Uses `surfaceContainerHighest` with alpha for overlay buttons and `LinearProgressIndicator` at the bottom to show state.
- **Transitions:** Uses `Crossfade` when changing thumbnails or states.

#### Text Fields
- Custom `SealTextField` implementation removes the default background container (`Color.Transparent`) for a cleaner look, relying on the bottom indicator and labels for structure.

---

### 3.3. Navigation & Layout

#### Single Activity Architecture
The app runs in a single `MainActivity` using a `NavHost` to manage "Pages".

#### Adaptive Navigation
The app dynamically switches navigation patterns based on `WindowWidthSizeClass`:
- **Compact (<600dp):** Modal Navigation Drawer (hamburger menu).
- **Expanded (>840dp):** Permanent Navigation Rail on the left, which can trigger a Modal Navigation Drawer for deeper settings.

#### Top Bar Behavior
The "collapsing" top bar effect is achieved using a custom `NestedScrollConnection` that offsets the header based on the scroll position of the main content list.

---

### 3.4. Motion & Animations

Motion is used to provide context and feedback, following the Material "Shared Axis" pattern.

#### Navigation Transitions
The app implements three types of Shared Axis transitions:
- **X-Axis:** For moving between peer destinations (e.g., Home to Task List).
- **Y-Axis:** For vertical drill-downs.
- **Z-Axis:** For entering/exiting specific items or dialogs.

#### Custom Easings
```kotlin
// A custom "Emphasize" easing for more natural movements
val EmphasizeEasing = PathEasing(Path().apply {
    moveTo(0f, 0f)
    cubicTo(0.05F, 0F, 0.133333F, 0.06F, 0.166666F, 0.4F)
    cubicTo(0.208333F, 0.82F, 0.25F, 1F, 1F, 1F)
})
```

---

## 4. Applying to BitPerfect

To achieve this aesthetic in the BitPerfect project, the following steps are recommended:

1.  **Integrate Material 3:** Ensure `androidx.compose.material3` is the primary UI dependency.
2.  **Implement a Tonal Palette Provider:** Use a library like `material-color-utilities` or a custom Monet module to generate theme colors dynamically.
3.  **Standardize Components:** Create a library of internal "BitPerfect" components (Buttons, Cards, Rows) that wrap M3 components with the desired padding, shapes, and color roles.
4.  **Adopt Shared Axis Navigation:** Use `AnimatedContent` or custom navigation transition specs to replace standard fade/slide animations.
5.  **Edge-to-Edge:** Use `enableEdgeToEdge()` in the Activity and handle `WindowInsets` properly to allow the UI to draw behind the status and navigation bars, as seen in the Seal screenshots.

---

## 5. Visual Reference

| Feature | Description | Screenshot Reference |
| :--- | :--- | :--- |
| **Main Screen** | Dynamic colors, Elevated Card, Floating Action Button. | [Screenshot 1](https://github.com/JunkFood02/Seal/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg) |
| **Configuration** | Modal Bottom Sheet with Tonal Buttons and Chips. | [Screenshot 2](https://github.com/JunkFood02/Seal/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg) |
| **Selection** | List view with Checkboxes and high-contrast labels. | [Screenshot 3](https://github.com/JunkFood02/Seal/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg) |
