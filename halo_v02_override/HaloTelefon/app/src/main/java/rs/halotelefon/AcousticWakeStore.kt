package rs.halotelefon

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Local, speaker-personalized wake phrase matcher.
 *
 * It never turns the wake phrase into text. Training stores only MFCC-like
 * feature sequences derived from the five local recordings. Incoming speech
 * segments are compared against those templates with DTW.
 */
class AcousticWakeStore(context: Context) {
    data class Match(
        val matched: Boolean,
        val distance: Double,
        val threshold: Double,
        val confidence: Int
    )

    private val dir = File(context.filesDir, "wake_profile").apply { mkdirs() }
    private val templates = mutableListOf<Array<FloatArray>>()

    init { reload() }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        templates.clear()
    }

    @Synchronized
    fun count(): Int = templates.size

    @Synchronized
    fun isReady(): Boolean = templates.size >= 3

    @Synchronized
    fun addSample(audio: FloatArray): Int {
        val features = WakeFeatures.extract(audio)
        require(features.size >= 25) { "Izgovor je prekratak. Reci celu frazu ‘Halo telefon’." }
        require(features.size <= 320) { "Izgovor je predugačak. Reci samo ‘Halo telefon’." }

        val index = templates.size
        val file = File(dir, "wake_${index.toString().padStart(2, '0')}.wf")
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(features.size)
            out.writeInt(features[0].size)
            for (row in features) for (v in row) out.writeFloat(v)
        }
        templates += features
        return templates.size
    }

    @Synchronized
    fun match(audio: FloatArray): Match {
        if (templates.size < 3) return Match(false, 99.0, 0.0, 0)
        val query = WakeFeatures.extract(audio)
        if (query.size < 20 || query.size > 340) return Match(false, 99.0, threshold(), 0)

        val distances = templates.map { WakeFeatures.dtw(query, it) }.sorted()
        val distance = distances.firstOrNull() ?: return Match(false, 99.0, threshold(), 0)
        val threshold = threshold()

        val templateLengths = templates.map { it.size }.sorted()
        val medianLength = templateLengths[templateLengths.size / 2].coerceAtLeast(1)
        val durationRatio = query.size.toDouble() / medianLength.toDouble()
        val plausibleDuration = durationRatio in 0.55..1.85
        val matched = plausibleDuration && distance <= threshold

        val confidence = if (!plausibleDuration) 0 else
            ((1.0 - distance / (threshold * 1.20)) * 100.0)
                .toInt().coerceIn(0, 100)
        return Match(matched, distance, threshold, confidence)
    }

    private fun threshold(): Double {
        if (templates.size < 2) return 0.62
        val pairwise = mutableListOf<Double>()
        for (i in 0 until templates.lastIndex) {
            for (j in i + 1 until templates.size) {
                pairwise += WakeFeatures.dtw(templates[i], templates[j])
            }
        }
        if (pairwise.isEmpty()) return 0.62
        val center = median(pairwise)
        val mad = median(pairwise.map { abs(it - center) })
        val learned = max(center * 1.55, center + max(0.16, mad * 4.0))
        return max(0.90, learned).coerceAtMost(1.05)
    }

    private fun reload() {
        templates.clear()
        dir.listFiles { f -> f.extension == "wf" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching {
                    DataInputStream(file.inputStream().buffered()).use { input ->
                        val rows = input.readInt()
                        val cols = input.readInt()
                        require(rows in 1..400 && cols in 1..32)
                        Array(rows) { FloatArray(cols) { input.readFloat() } }
                    }
                }.getOrNull()?.let(templates::add)
            }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}

private object WakeFeatures {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME = 400
    private const val HOP = 160
    private const val FFT_N = 512
    private const val MEL_BANDS = 20
    private const val COEFFS = 12

    private val hamming = DoubleArray(FRAME) { i ->
        0.54 - 0.46 * cos(2.0 * PI * i / (FRAME - 1))
    }
    private val melFilters = buildMelFilters()
    private val dct = Array(COEFFS) { c ->
        DoubleArray(MEL_BANDS) { m ->
            cos(PI * c * (m + 0.5) / MEL_BANDS)
        }
    }

    fun extract(raw: FloatArray): Array<FloatArray> {
        if (raw.isEmpty()) return emptyArray()
        val samples = normalizeAudio(trimSilence(raw))
        if (samples.size < FRAME) return emptyArray()
        val frames = 1 + (samples.size - FRAME) / HOP
        val out = Array(frames) { FloatArray(COEFFS) }

        for (f in 0 until frames) {
            val start = f * HOP
            val re = DoubleArray(FFT_N)
            val im = DoubleArray(FFT_N)
            for (i in 0 until FRAME) re[i] = samples[start + i] * hamming[i]
            fft(re, im)

            val power = DoubleArray(FFT_N / 2 + 1) { k ->
                re[k] * re[k] + im[k] * im[k]
            }
            val mel = DoubleArray(MEL_BANDS)
            for (m in 0 until MEL_BANDS) {
                var e = 0.0
                val filter = melFilters[m]
                for (k in power.indices) e += power[k] * filter[k]
                mel[m] = ln(max(e, 1e-10))
            }
            for (c in 1..COEFFS) {
                var v = 0.0
                val basis = dct[c - 1]
                for (m in 0 until MEL_BANDS) v += mel[m] * basis[m]
                out[f][c - 1] = v.toFloat()
            }
        }

        for (c in 0 until COEFFS) {
            var mean = 0.0
            for (r in out.indices) mean += out[r][c]
            mean /= out.size
            var variance = 0.0
            for (r in out.indices) {
                val d = out[r][c] - mean
                variance += d * d
            }
            val std = sqrt(variance / out.size).coerceAtLeast(1e-4)
            for (r in out.indices) out[r][c] = ((out[r][c] - mean) / std).toFloat()
        }
        return out
    }

    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        if (a.isEmpty() || b.isEmpty()) return 99.0
        val n = a.size
        val m = b.size
        val band = max(12, max(n, m) / 3)
        val inf = 1e30
        var prev = DoubleArray(m + 1) { inf }
        var curr = DoubleArray(m + 1) { inf }
        var prevSteps = IntArray(m + 1) { Int.MAX_VALUE / 4 }
        var currSteps = IntArray(m + 1) { Int.MAX_VALUE / 4 }
        prev[0] = 0.0
        prevSteps[0] = 0

        for (i in 1..n) {
            java.util.Arrays.fill(curr, inf)
            java.util.Arrays.fill(currSteps, Int.MAX_VALUE / 4)
            val j0 = max(1, i - band)
            val j1 = min(m, i + band)
            for (j in j0..j1) {
                val local = cosineDistance(a[i - 1], b[j - 1])
                var best = prev[j]
                var steps = prevSteps[j]
                if (curr[j - 1] < best) {
                    best = curr[j - 1]
                    steps = currSteps[j - 1]
                }
                if (prev[j - 1] < best) {
                    best = prev[j - 1]
                    steps = prevSteps[j - 1]
                }
                curr[j] = local + best
                currSteps[j] = steps + 1
            }
            val td = prev; prev = curr; curr = td
            val ts = prevSteps; prevSteps = currSteps; currSteps = ts
        }
        val steps = prevSteps[m].coerceAtLeast(1)
        return prev[m] / steps
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na * nb).coerceAtLeast(1e-9)
        return (1.0 - dot / denom).coerceIn(0.0, 2.0)
    }

    private fun trimSilence(input: FloatArray): FloatArray {
        val block = 160
        val rms = mutableListOf<Double>()
        var p = 0
        while (p < input.size) {
            val end = min(input.size, p + block)
            var s = 0.0
            for (i in p until end) s += input[i] * input[i]
            rms += sqrt(s / (end - p).coerceAtLeast(1))
            p = end
        }
        val peak = rms.maxOrNull() ?: return input
        val threshold = max(0.004, peak * 0.12)
        val first = rms.indexOfFirst { it >= threshold }.let { if (it < 0) 0 else it }
        val last = rms.indexOfLast { it >= threshold }.let { if (it < 0) rms.lastIndex else it }
        val start = max(0, (first - 2) * block)
        val end = min(input.size, (last + 3) * block)
        return if (end > start) input.copyOfRange(start, end) else input
    }

    private fun normalizeAudio(input: FloatArray): FloatArray {
        var mean = 0.0
        for (v in input) mean += v
        mean /= input.size.coerceAtLeast(1)
        var energy = 0.0
        for (v in input) {
            val d = v - mean
            energy += d * d
        }
        val rms = sqrt(energy / input.size.coerceAtLeast(1)).coerceAtLeast(1e-4)
        val gain = min(8.0, 0.12 / rms)
        return FloatArray(input.size) { i -> ((input[i] - mean) * gain).toFloat() }
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang)
            val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + half] * wr - im[i + k + half] * wi
                    val vI = re[i + k + half] * wi + im[i + k + half] * wr
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + half] = uR - vR
                    im[i + k + half] = uI - vI
                    val nwr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun buildMelFilters(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val minMel = hzToMel(180.0)
        val maxMel = hzToMel(7600.0)
        val points = DoubleArray(MEL_BANDS + 2) { i ->
            minMel + (maxMel - minMel) * i / (MEL_BANDS + 1)
        }
        val bins = IntArray(points.size) { i ->
            (((FFT_N + 1) * melToHz(points[i]) / SAMPLE_RATE).toInt())
                .coerceIn(0, FFT_N / 2)
        }
        return Array(MEL_BANDS) { m ->
            val f = DoubleArray(FFT_N / 2 + 1)
            val left = bins[m]
            val center = max(left + 1, bins[m + 1])
            val right = max(center + 1, bins[m + 2]).coerceAtMost(FFT_N / 2)
            for (k in left until center) f[k] = (k - left).toDouble() / (center - left)
            for (k in center..right) f[k] = (right - k).toDouble() / (right - center)
            f
        }
    }
}
