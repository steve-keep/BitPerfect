sed -i 's/lba=${currentChunk.startLba}/lba=${suspiciousRead.startLba}/g' app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt
