with open("app/src/main/kotlin/com/bitperfect/app/usb/ChecksumAccumulator.kt", "r") as f:
    content = f.read()

old_code = """internal class ChecksumAccumulator(
    private val verifier: AccurateRipVerifier,
    private val totalSamples: Long,
    private val driveOffset: Int = 0,
    private val isFirstTrack: Boolean = false,
    private val isLastTrack: Boolean = false
) {
    var ripChecksum: Long = 0L
        private set
    var samplePosition: Long = if (driveOffset < 0) 1L + driveOffset else 1L
        private set"""

new_code = """internal class ChecksumAccumulator(
    private val verifier: AccurateRipVerifier,
    private val totalSamples: Long,
    private val isFirstTrack: Boolean = false,
    private val isLastTrack: Boolean = false
) {
    var ripChecksum: Long = 0L
        private set
    var samplePosition: Long = 1L
        private set"""

content = content.replace(old_code, new_code)

with open("app/src/main/kotlin/com/bitperfect/app/usb/ChecksumAccumulator.kt", "w") as f:
    f.write(content)
