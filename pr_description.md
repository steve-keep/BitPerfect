# 🧹 Replace placeholder with actual monochrome notification icon

## Task Details
Replaced the `R.mipmap.ic_launcher` used in `RipService.kt` with a new monochrome disc vector drawable `R.drawable.ic_notification`, fulfilling the TODO comment.

🎯 **What:** Created `ic_notification.xml` (a simple disc vector icon) and referenced it in `NotificationCompat.Builder`'s `.setSmallIcon()`.

💡 **Why:** Android guidelines require notification icons to be monochrome. Using the standard colored launcher icon in modern Android versions results in an unstyled white square or tinting issues in the status bar. This change guarantees the notification displays correctly with the system's applied tint.

✅ **Verification:** Validated that the app compiles perfectly and unit tests successfully passed (`./gradlew :app:testDebugUnitTest`). There are no behavioral changes in the app's control flow.

✨ **Result:** A properly styled monochrome notification icon for the CD ripping service, replacing the old technical debt placeholder.
