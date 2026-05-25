cat << 'PATCH_EOF' > ripmanager.patch
--- app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
+++ app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
@@ -395,7 +395,8 @@
                                         recoveryWindowEndLba = finalMetadata.recoveryWindowEndLba,
                                         strategy = finalMetadata.strategy,
                                         rereadAttempts = totalAttempts,
-                                        recovered = finalMetadata.recovered
+                                        recovered = finalMetadata.recovered,
+                                        anomaly = finalMetadata.anomaly
                                     )
                                 } else {
                                     SuspiciousRead(
@@ -405,14 +406,16 @@
                                         recoveryWindowEndLba = null,
                                         strategy = null,
                                         rereadAttempts = 0,
-                                        recovered = false
+                                        recovered = false,
+                                        anomaly = null
                                     )
                                 }

                                 currentChunkConfidence = confidenceEvaluator.evaluateChunkConfidence(
                                     overlapMatchedImmediately = false,
                                     rereadsPerformed = suspiciousRead.rereadAttempts,
-                                    recoverySucceeded = suspiciousRead.recovered
+                                    recoverySucceeded = suspiciousRead.recovered,
+                                    anomaly = suspiciousRead.anomaly
                                 )

                                 currentChunk = when (recoveryResult) {
@@ -424,6 +427,20 @@
                                 currentSuspiciousRegions.add(suspiciousRead)
                                 _trackStates.value = _trackStates.value + (trackNumber to state.copy(suspiciousRegions = currentSuspiciousRegions))

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
                             } else {
                                 // Reread engine returned null chunk (shouldn't happen with MaxRereads fallback, but handle just in case)
                                 currentChunkConfidence = RipConfidence.LOW
PATCH_EOF
patch app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt < ripmanager.patch
