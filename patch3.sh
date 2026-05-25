sed -i '/val currentSuspiciousRegions = state.suspiciousRegions.toMutableList()/!b;n;a\
\
                                suspiciousRead.anomaly?.let { anomaly ->\
                                    when (anomaly) {\
                                        is AlignmentAnomaly.PossibleShift -> {\
                                            AppLogger.w("RipManager", "drift_suspicion track=$trackNumber lba=${currentChunk.startLba} shift=${anomaly.sampleDelta} confidence=${anomaly.confidence}")\
                                        }\
                                        is AlignmentAnomaly.SevereInstability -> {\
                                            AppLogger.w("RipManager", "severe_instability track=$trackNumber lba=${currentChunk.startLba} mismatches=${anomaly.mismatchCount}")\
                                        }\
                                        is AlignmentAnomaly.None -> {\
                                            // No-op\
                                        }\
                                    }\
                                }' app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
