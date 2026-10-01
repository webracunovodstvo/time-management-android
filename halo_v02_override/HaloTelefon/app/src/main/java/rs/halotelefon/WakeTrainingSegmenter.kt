package rs.halotelefon

import kotlin.math.max
import kotlin.math.sqrt

/**
 * More permissive VAD used ONLY while recording the 5 "Halo telefon"
 * training samples. Normal wake listening keeps the stricter VoiceSegmenter.
 */
class WakeTrainingSegmenter(
    private val sampleRate: Int = 16_000
) {
    var speechStarted: Boolean = false
        private set

    private var noiseFloor = 0.003
    private var speechFrames = 0
    private var silenceFrames = 0
    private val segment = ArrayList<Float>(sampleRate * 4)
    private val preRoll = ArrayDeque<FloatArray>()

    fun reset() {
        speechStarted = false
        speechFrames = 0
        silenceFrames = 0
        segment.clear()
        preRoll.clear()
    }

    fun accept(
        frame: FloatArray,
        maxSeconds: Double = 3.0
    ): FloatArray? {
        val rms = rms(frame)
        val threshold =
            max(0.0032, noiseFloor * 1.30)

        if (!speechStarted) {
            if (rms < threshold * 1.20) {
                noiseFloor =
                    (noiseFloor * 0.98 + rms * 0.02)
                        .coerceIn(0.0010, 0.06)
            }

            preRoll.addLast(frame.copyOf())
            while (preRoll.size > 18) {
                preRoll.removeFirst()
            }

            if (rms > threshold) {
                speechFrames++
            } else {
                speechFrames = 0
            }

            if (speechFrames >= 1) {
                speechStarted = true
                preRoll.forEach { f ->
                    f.forEach(segment::add)
                }
                preRoll.clear()
                silenceFrames = 0
            }

            return null
        }

        frame.forEach(segment::add)

        val silenceThreshold =
            max(0.0028, threshold * 0.68)

        if (rms < silenceThreshold) {
            silenceFrames++
        } else {
            silenceFrames = 0
        }

        val duration =
            segment.size.toDouble() / sampleRate

        val endedBySilence =
            silenceFrames >= 16 &&
                duration >= 0.28

        val endedByLength =
            duration >= maxSeconds

        if (endedBySilence || endedByLength) {
            val out = segment.toFloatArray()
            reset()
            return out
        }

        return null
    }

    private fun rms(frame: FloatArray): Double {
        var sum = 0.0
        for (v in frame) {
            sum += v * v
        }
        return sqrt(
            sum / frame.size.coerceAtLeast(1)
        )
    }
}
