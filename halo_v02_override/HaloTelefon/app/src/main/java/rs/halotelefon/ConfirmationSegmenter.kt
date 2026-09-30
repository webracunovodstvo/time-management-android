package rs.halotelefon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * V0.11 capture for very short confirmation commands such as:
 * "zovi", "ok", "okej", "može".
 *
 * Unlike NameSegmenter it is allowed to finish after ~0.28 s of speech,
 * because padding is added before Whisper inference.
 * It can still capture a replacement contact name up to 3.2 s.
 */
class ConfirmationSegmenter(
    private val sampleRate: Int = 16_000
) {
    var speechStarted: Boolean = false
        private set

    var timedOut: Boolean = false
        private set

    private var noiseFloor = 0.0035
    private var speechFrames = 0
    private var silenceFrames = 0
    private var waitingFrames = 0
    private var peakRms = 0.0

    private val preRoll = ArrayDeque<FloatArray>()
    private val segment = ArrayList<Float>(sampleRate * 3)

    fun reset() {
        speechStarted = false
        timedOut = false
        speechFrames = 0
        silenceFrames = 0
        waitingFrames = 0
        peakRms = 0.0
        preRoll.clear()
        segment.clear()
    }

    fun accept(frame: FloatArray): FloatArray? {
        val rms = rms(frame)

        if (!speechStarted) {
            waitingFrames++

            if (waitingFrames > 500) { // ~10 seconds: enough time to read 5 candidates
                timedOut = true
                return null
            }

            if (rms < max(0.014, noiseFloor * 2.2)) {
                noiseFloor = (noiseFloor * 0.97 + rms * 0.03)
                    .coerceIn(0.0012, 0.05)
            }

            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 8) {
                preRoll.removeFirst()
            }

            val startThreshold = max(0.0028, noiseFloor * 1.28)

            if (rms > startThreshold) {
                speechFrames++
            } else {
                speechFrames = 0
            }

            if (speechFrames >= 1) {
                speechStarted = true
                preRoll.forEach { chunk ->
                    chunk.forEach(segment::add)
                }
                preRoll.clear()
                peakRms = rms
                silenceFrames = 0
            }

            return null
        }

        frame.forEach(segment::add)
        peakRms = max(peakRms, rms)

        val endThreshold = max(
            0.0026,
            max(noiseFloor * 1.15, peakRms * 0.065)
        )

        if (rms < endThreshold) {
            silenceFrames++
        } else {
            silenceFrames = 0
        }

        val duration = segment.size.toDouble() / sampleRate

        // Short confirmations should finish quickly, but full replacement
        // names still have room to continue.
        val endedBySilence =
            duration >= 0.28 && silenceFrames >= 10 // ~200 ms

        val endedByLength = duration >= 3.2

        if (endedBySilence || endedByLength) {
            val out = segment.toFloatArray()
            reset()
            return out
        }

        return null
    }

    private fun rms(frame: FloatArray): Double {
        var sum = 0.0
        for (sample in frame) {
            sum += sample * sample
        }
        return sqrt(sum / frame.size.coerceAtLeast(1))
    }
}
