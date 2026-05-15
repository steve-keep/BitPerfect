package com.bitperfect.app.usb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class AudioAnalysis(
    val bpm: Float,
    val initialKey: String,
    val replayGainDb: Float,
    val replayGainPeak: Float,
    val energy: Float
)

class AudioAnalyser {
    private val stftWindowSize = 4096
    private val stftHopSize = 2048
    private val stftBuffer = FloatArray(stftWindowSize)
    private var stftBufferPos = 0

    private val bpmWindowSize = 512
    private val bpmHopSize = 256
    private val bpmBuffer = FloatArray(bpmWindowSize)
    private var bpmBufferPos = 0

    // BPM variables
    private var prevMag = FloatArray(bpmWindowSize / 2)
    private val onsetEnvelope = mutableListOf<Float>()

    // Chroma variables
    private val chromaBins = FloatArray(12)

    // ReplayGain variables
    private val rgBlockSize = 2205 // 50ms at 44.1kHz
    private val rgBuffer = FloatArray(rgBlockSize)
    private var rgBufferPos = 0
    private val rgRmsBlocks = mutableListOf<Float>()
    private var maxPeak = 0f

    // Energy variables
    private var totalSquareSum = 0.0
    private var totalSamples = 0L

    // ReplayGain 1.0 Filter State (for 44100 Hz)
    // Filter 1: Yule-Walker
    private val a1 = doubleArrayOf(1.0, -3.846646171180674, 7.820698287537330, -11.33642596489436, 13.06173003049179, -12.21323321528641, 9.255476344243616, -5.513567439537243, 2.378943033503254, -0.6698188166946022)
    private val b1 = doubleArrayOf(1.111812497678129, -4.568430737475149, 9.537549040842513, -13.56064041796537, 14.62955437877997, -12.63750036616091, 8.847551061919864, -4.847525368305711, 1.884570086202422, -0.4704381282914101)
    // Filter 2: Butterworth High Pass
    private val a2 = doubleArrayOf(1.0, -1.986820541740925, 0.986877894371497)
    private val b2 = doubleArrayOf(0.993444007823528, -1.986888015647055, 0.993444007823528)

    private val x1 = DoubleArray(10)
    private val y1 = DoubleArray(10)
    private val x2 = DoubleArray(3)
    private val y2 = DoubleArray(3)
    private var filterIdx1 = 0
    private var filterIdx2 = 0

    // Krumhansl-Schmuckler profiles
    private val majorProfile = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val minorProfile = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    // Camelot Wheel Mapping (Major, Minor) for C, C#, D, D#, E, F, F#, G, G#, A, A#, B
    private val camelotMajor = arrayOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")
    private val camelotMinor = arrayOf("5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A")

    fun feed(chunk: ByteArray) {
        val samples = chunk.size / 4 // 16-bit stereo = 4 bytes per sample frame
        for (i in 0 until samples) {
            val idx = i * 4
            // Little-endian parsing
            val left = (chunk[idx].toInt() and 0xFF) or (chunk[idx + 1].toInt() shl 8)
            val right = (chunk[idx + 2].toInt() and 0xFF) or (chunk[idx + 3].toInt() shl 8)

            // Sign extend 16-bit
            val lShort = left.toShort()
            val rShort = right.toShort()

            // Normalised -1.0 to 1.0 mono mix
            val mono = ((lShort + rShort) / 2.0f) / 32768.0f

            // Peak tracking (absolute maximum sample value)
            val lAbs = kotlin.math.abs(lShort.toFloat() / 32768.0f)
            val rAbs = kotlin.math.abs(rShort.toFloat() / 32768.0f)
            if (lAbs > maxPeak) maxPeak = lAbs
            if (rAbs > maxPeak) maxPeak = rAbs

            // Energy tracking
            totalSquareSum += (mono * mono).toDouble()
            totalSamples++

            // ReplayGain filtering and blocking
            val filteredMono = applyReplayGainFilter(mono.toDouble()).toFloat()
            rgBuffer[rgBufferPos++] = filteredMono
            if (rgBufferPos == rgBlockSize) {
                var blockSum = 0.0
                for (j in 0 until rgBlockSize) {
                    blockSum += rgBuffer[j] * rgBuffer[j]
                }
                rgRmsBlocks.add(sqrt(blockSum / rgBlockSize).toFloat())
                rgBufferPos = 0
            }

            // BPM STFT (512 window, 256 hop)
            bpmBuffer[bpmBufferPos++] = mono
            if (bpmBufferPos == bpmWindowSize) {
                processBpmWindow()
                // Shift down by hop size
                System.arraycopy(bpmBuffer, bpmHopSize, bpmBuffer, 0, bpmWindowSize - bpmHopSize)
                bpmBufferPos -= bpmHopSize
            }

            // Chroma STFT (4096 window, 2048 hop)
            stftBuffer[stftBufferPos++] = mono
            if (stftBufferPos == stftWindowSize) {
                processChromaWindow()
                // Shift down by hop size
                System.arraycopy(stftBuffer, stftHopSize, stftBuffer, 0, stftWindowSize - stftHopSize)
                stftBufferPos -= stftHopSize
            }
        }
    }

    private fun applyReplayGainFilter(sample: Double): Double {
        // Filter 1: Yule-Walker
        x1[filterIdx1] = sample
        var y1_val = b1[0] * x1[filterIdx1]
        for (i in 1..9) {
            val idx = (filterIdx1 - i + 10) % 10
            y1_val += b1[i] * x1[idx] - a1[i] * y1[idx]
        }
        y1[filterIdx1] = y1_val
        filterIdx1 = (filterIdx1 + 1) % 10

        // Filter 2: Butterworth
        x2[filterIdx2] = y1_val
        var y2_val = b2[0] * x2[filterIdx2]
        for (i in 1..2) {
            val idx = (filterIdx2 - i + 3) % 3
            y2_val += b2[i] * x2[idx] - a2[i] * y2[idx]
        }
        y2[filterIdx2] = y2_val
        filterIdx2 = (filterIdx2 + 1) % 3

        return y2_val
    }

    private fun processBpmWindow() {
        val windowed = FloatArray(bpmWindowSize)
        for (i in 0 until bpmWindowSize) {
            // Hann window
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (bpmWindowSize - 1)))
            windowed[i] = bpmBuffer[i] * w
        }

        val complex = FloatArray(bpmWindowSize * 2)
        for (i in 0 until bpmWindowSize) {
            complex[2 * i] = windowed[i]
            complex[2 * i + 1] = 0f
        }

        fft(complex, bpmWindowSize)

        var flux = 0f
        for (i in 0 until bpmWindowSize / 2) {
            val re = complex[2 * i]
            val im = complex[2 * i + 1]
            val mag = sqrt(re * re + im * im)
            val diff = mag - prevMag[i]
            if (diff > 0) flux += diff
            prevMag[i] = mag
        }
        onsetEnvelope.add(flux)
    }

    private fun processChromaWindow() {
        val windowed = FloatArray(stftWindowSize)
        for (i in 0 until stftWindowSize) {
            // Hann window
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (stftWindowSize - 1)))
            windowed[i] = stftBuffer[i] * w
        }

        val complex = FloatArray(stftWindowSize * 2)
        for (i in 0 until stftWindowSize) {
            complex[2 * i] = windowed[i]
            complex[2 * i + 1] = 0f
        }

        fft(complex, stftWindowSize)

        for (i in 1 until stftWindowSize / 2) { // Skip DC
            val re = complex[2 * i]
            val im = complex[2 * i + 1]
            val mag = sqrt(re * re + im * im)

            val freq = i * 44100.0f / stftWindowSize
            if (freq > 0) {
                // MIDI note number (A4 = 69 = 440Hz)
                val note = 69 + 12 * (log10(freq / 440.0f) / log10(2.0f))
                val bin = (kotlin.math.round(note) % 12).toInt()
                // Note: low-frequency content below MIDI 0 (note < 0) will yield a negative bin.
                // This is irrelevant for chroma and is deliberately excluded here.
                if (bin in 0..11) {
                    chromaBins[bin] += mag
                }
            }
        }
    }

    private fun fft(data: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var temp = data[2 * i]
                data[2 * i] = data[2 * j]
                data[2 * j] = temp
                temp = data[2 * i + 1]
                data[2 * i + 1] = data[2 * j + 1]
                data[2 * j + 1] = temp
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        // Cooley-Tukey
        var mMax = 1
        while (n > mMax) {
            val step = mMax shl 1
            val theta = (-PI / mMax).toFloat()
            val wPr = cos(theta)
            val wPi = sin(theta)
            var wRe = 1f
            var wIm = 0f
            for (m in 0 until mMax) {
                for (i in m until n step step) {
                    val idx1 = 2 * i
                    val idx2 = 2 * (i + mMax)
                    val tr = wRe * data[idx2] - wIm * data[idx2 + 1]
                    val ti = wRe * data[idx2 + 1] + wIm * data[idx2]
                    data[idx2] = data[idx1] - tr
                    data[idx2 + 1] = data[idx1 + 1] - ti
                    data[idx1] += tr
                    data[idx1 + 1] += ti
                }
                val t = wRe
                wRe = wRe * wPr - wIm * wPi
                wIm = wIm * wPr + t * wPi
            }
            mMax = step
        }
    }

    fun analyse(): AudioAnalysis {
        // --- 1. BPM ---
        var bpm = 0f
        if (onsetEnvelope.size >= 8 && onsetEnvelope.sum() > 0.001f) {
            // Autocorrelation over range 60-200 BPM
            // hopSize = 256 samples @ 44100 = 5.8ms per envelope point
            // 60 BPM = 1 beat per sec = 44100 / 256 = 172.26 points
            // 200 BPM = 3.33 beats per sec = 44100 / (200/60) / 256 = 51.68 points
            val minLag = (44100.0f / (200.0f / 60.0f) / bpmHopSize).toInt()
            val maxLag = (44100.0f / (60.0f / 60.0f) / bpmHopSize).toInt()

            var bestLag = -1
            var bestAutocorr = -1f

            for (lag in minLag..maxLag) {
                var sum = 0f
                for (i in 0 until onsetEnvelope.size - lag) {
                    sum += onsetEnvelope[i] * onsetEnvelope[i + lag]
                }
                sum /= (onsetEnvelope.size - lag)
                if (sum > bestAutocorr) {
                    bestAutocorr = sum
                    bestLag = lag
                }
            }

            if (bestLag > 0) {
                val beatsPerSecond = (44100.0f / bpmHopSize) / bestLag
                bpm = beatsPerSecond * 60f
            }
        }

        // --- 2. Initial Key ---
        var bestKey = "C"
        var bestScore = -Float.MAX_VALUE

        // Normalise chroma bins
        val maxChroma = chromaBins.maxOrNull() ?: 1f
        if (maxChroma > 0) {
            for (i in 0..11) {
                chromaBins[i] /= maxChroma
            }
        }

        if (maxChroma > 0f && chromaBins.sum() > 0.001f) {
            for (i in 0..11) {
                // Major
                var scoreMajor = 0f
                for (j in 0..11) {
                    scoreMajor += chromaBins[(i + j) % 12] * majorProfile[j]
                }
                if (scoreMajor > bestScore) {
                    bestScore = scoreMajor
                    bestKey = camelotMajor[i]
                }

                // Minor
                var scoreMinor = 0f
                for (j in 0..11) {
                    scoreMinor += chromaBins[(i + j) % 12] * minorProfile[j]
                }
                if (scoreMinor > bestScore) {
                    bestScore = scoreMinor
                    bestKey = camelotMinor[i]
                }
            }
        } else {
            bestKey = ""
        }

        // --- 3. ReplayGain ---
        var replayGainDb = 0f
        if (rgRmsBlocks.isNotEmpty()) {
            rgRmsBlocks.sort()
            val percentile95Idx = (rgRmsBlocks.size * 0.95).toInt()
            val loudnessRms95 = rgRmsBlocks[kotlin.math.min(percentile95Idx, rgRmsBlocks.size - 1)]
            // ReplayGain spec: 89 - 20*log10(RMS_16bit) where RMS_16bit is scaled to 32768
            val rms16bit = loudnessRms95 * 32768.0
            if (rms16bit > 0) {
                replayGainDb = (89.0 - 20.0 * log10(rms16bit)).toFloat()
            }
        }

        // --- 4. Energy ---
        var energy = 0f
        if (totalSamples > 0) {
            val rms = sqrt(totalSquareSum / totalSamples).toFloat()
            // 0.15 ≈ typical RMS of a moderately loud signal; maps ~0.05→0.32, ~0.15→0.76, ~0.30→0.97
            energy = tanh(rms / 0.15f)
        }

        return AudioAnalysis(
            bpm = bpm,
            initialKey = bestKey,
            replayGainDb = replayGainDb,
            replayGainPeak = maxPeak,
            energy = energy
        )
    }
}
