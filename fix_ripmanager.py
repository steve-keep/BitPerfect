import re

with open("app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt", "r") as f:
    content = f.read()

# Fix the LBA and sectorsRead logic
old_loop_setup = """                val chunkSize = 8 // read ~8 sectors at a time
                val lbaStart = entry.lba + tocOffset + if (overreadBuffer != null) 1 else 0

                if (overreadBuffer != null) {
                    encoder.encode(overreadBuffer!!)
                    checksumAccumulator.accumulate(overreadBuffer!!)
                }

                var isFirstSector = true

                while (sectorsRead < totalSectors && !isCancelled) {"""

new_loop_setup = """                val chunkSize = 8 // read ~8 sectors at a time
                val lbaStart = entry.lba + tocOffset

                if (overreadBuffer != null) {
                    encoder.encode(overreadBuffer!!)
                    checksumAccumulator.accumulate(overreadBuffer!!)
                    sectorsRead = 1
                }

                var isFirstSector = overreadBuffer == null

                while (sectorsRead < totalSectors && !isCancelled) {"""

content = content.replace(old_loop_setup, new_loop_setup)

with open("app/src/main/kotlin/com/bitperfect/app/usb/RipManager.kt", "w") as f:
    f.write(content)
