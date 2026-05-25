cat << 'PATCH_EOF' > ripmanager.patch
--- app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
+++ app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
@@ -434,6 +434,20 @@
                                 val currentSuspiciousRegions = state.suspiciousRegions.toMutableList()
                                 currentSuspiciousRegions.add(suspiciousRead)

+                                suspiciousRead.anomaly?.let { anomaly ->
+                                    when (anomaly) {
+                                        is AlignmentAnomaly.PossibleShift -> {
+                                            AppLogger.w("RipManager", "drift_suspicion track=\$trackNumber lba=\${suspiciousRead.startLba} shift=\${anomaly.sampleDelta} confidence=\${anomaly.confidence}")
+                                        }
+                                        is AlignmentAnomaly.SevereInstability -> {
+                                            AppLogger.w("RipManager", "severe_instability track=\$trackNumber lba=\${suspiciousRead.startLba} mismatches=\${anomaly.mismatchCount}")
+                                        }
+                                        is AlignmentAnomaly.None -> {
+                                            // No-op
+                                        }
+                                    }
+                                }
+
                                 updateTrackState(
                                     trackNumber = trackNumber,
                                     status = state.status,
PATCH_EOF
patch app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt < ripmanager.patch
