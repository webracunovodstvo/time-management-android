package rs.halotelefon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dedicated capture logic for the contact name after the wake phrase.
 * Much more permissive than the always-on wake VAD.
 */
class NameSegmenter(
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
            if (waitingFrames > 300) { // ~6 s at 20 ms per frame
                timedOut = true
                return null
            }

            noiseFloor = if (rms < max(0.015, noiseFloor * 2.5)) {
                (noiseFloor * 0.97 + rms * 0.03).coerceIn(0.0012, 0.05)
            } else {
                noiseFloor
            }

            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 8) preRoll.removeFirst()

            val startThreshold = max(0.0032, noiseFloor * 1.40)
            if (rms > startThreshold) speechFrames++ else speechFrames = 0

            if (speechFrames >= 2) {
                speechStarted = true
                preRoll.forEach { f -> f.forEach(segment::add) }
                preRoll.clear()
                peakRms = rms
                silenceFrames = 0
            }
            return null
        }

        frame.forEach(segment::add)
        peakRms = max(peakRms, rms)

        val endThreshold = max(0.0028, max(noiseFloor * 1.25, peakRms * 0.09))
        if (rms < endThreshold) silenceFrames++ else silenceFrames = 0

        val durationSeconds = segment.size.toDouble() / sampleRate
        val endedBySilence = silenceFrames >= 12 && durationSeconds >= 0.35
        val endedByLength = durationSeconds >= 3.0

        if (endedBySilence || endedByLength) {
            val out = segment.toFloatArray()
            reset()
            return out
        }
        return null
    }

    private fun rms(frame: FloatArray): Double {
        var sum = 0.0
        for (v in frame) sum += v * v
        return sqrt(sum / frame.size.coerceAtLeast(1))
    }
}
