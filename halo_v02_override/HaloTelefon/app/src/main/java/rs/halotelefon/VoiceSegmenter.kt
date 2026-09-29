package rs.halotelefon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sensitive adaptive VAD. False speech segments are acceptable because the
 * acoustic wake matcher rejects them; missing a quiet utterance is not.
 */
class VoiceSegmenter(
    private val sampleRate: Int = 16_000
) {
    private var noiseFloor = 0.004
    private var speechFrames = 0
    private var silenceFrames = 0
    private var inSpeech = false
    private val segment = ArrayList<Float>(sampleRate * 6)
    private val preRoll = ArrayDeque<FloatArray>()

    fun reset() {
        speechFrames = 0
        silenceFrames = 0
        inSpeech = false
        segment.clear()
        preRoll.clear()
    }

    fun accept(frame: FloatArray, maxSeconds: Double): FloatArray? {
        val rms = rms(frame)
        val threshold = max(0.006, noiseFloor * 1.75)

        if (!inSpeech) {
            if (rms < threshold * 1.15) {
                noiseFloor = (noiseFloor * 0.985 + rms * 0.015).coerceIn(0.0015, 0.08)
            }

            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 15) preRoll.removeFirst()

            if (rms > threshold) speechFrames++ else speechFrames = 0
            if (speechFrames >= 2) {
                inSpeech = true
                preRoll.forEach { f -> f.forEach(segment::add) }
                preRoll.clear()
                silenceFrames = 0
            }
            return null
        }

        frame.forEach(segment::add)
        if (rms < max(0.0045, threshold * 0.72)) silenceFrames++ else silenceFrames = 0

        val durationSeconds = segment.size.toDouble() / sampleRate
        val endedBySilence = silenceFrames >= 20 && durationSeconds >= 0.30
        val endedByLength = durationSeconds >= maxSeconds

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
