package rs.halotelefon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Dedicated capture for a short contact name.
 *
 * v0.22 closes the segment sooner after a real pause. The Serbian-tuned
 * model needs less padding/context than the old generic model, so this removes
 * a noticeable delay before contact matching without cutting normal full names.
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
    private val segment = ArrayList<Float>(sampleRate * 4)

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
            if (waitingFrames > 250) { // about 5 s
                timedOut = true
                return null
            }

            if (rms < max(0.015, noiseFloor * 2.4)) {
                noiseFloor = (noiseFloor * 0.97 + rms * 0.03).coerceIn(0.0012, 0.05)
            }

            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 12) preRoll.removeFirst() // ~240 ms

            val startThreshold = max(0.0045, noiseFloor * 1.65)
            if (rms > startThreshold) speechFrames++ else speechFrames = 0

            if (speechFrames >= 3) {
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

        val endThreshold = max(0.0028, max(noiseFloor * 1.18, peakRms * 0.075))
        if (rms < endThreshold) silenceFrames++ else silenceFrames = 0

        val durationSeconds = segment.size.toDouble() / sampleRate
        val longEnough = durationSeconds >= 0.55
        val endedBySilence = longEnough && silenceFrames >= 11 // ~220 ms
        val endedByLength = durationSeconds >= 3.2

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
