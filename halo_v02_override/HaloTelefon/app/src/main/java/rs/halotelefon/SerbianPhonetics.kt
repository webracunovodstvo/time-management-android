package rs.halotelefon

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Serbian phonetic layer for contact names.
 *
 * Serbian orthography is highly phonemic, but Latin digraphs lj/nj/dž
 * represent single letters/sounds. We therefore compare phoneme tokens,
 * not raw characters.
 */
object SerbianPhonetics {
    private val cyrillicToLatin = linkedMapOf(
        'а' to "a",  'б' to "b",  'в' to "v",  'г' to "g",
        'д' to "d",  'ђ' to "đ",  'е' to "e",  'ж' to "ž",
        'з' to "z",  'и' to "i",  'ј' to "j",  'к' to "k",
        'л' to "l",  'љ' to "lj", 'м' to "m",  'н' to "n",
        'њ' to "nj", 'о' to "o",  'п' to "p",  'р' to "r",
        'с' to "s",  'т' to "t",  'ћ' to "ć",  'у' to "u",
        'ф' to "f",  'х' to "h",  'ц' to "c",  'ч' to "č",
        'џ' to "dž", 'ш' to "š"
    )

    fun key(raw: String): String =
        phonemes(raw).joinToString("_")

    fun similarity(aRaw: String, bRaw: String): Double {
        val a = phonemes(aRaw)
        val b = phonemes(bRaw)

        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val distance = weightedDistance(a, b)
        val scale = max(a.size, b.size).coerceAtLeast(1).toDouble()
        return (1.0 - distance / scale).coerceIn(0.0, 1.0)
    }

    fun phonemes(raw: String): List<String> {
        val lower = raw.lowercase(Locale.ROOT)
        val latin = buildString(lower.length * 2) {
            for (ch in lower) {
                append(cyrillicToLatin[ch] ?: ch)
            }
        }
            .replace('q', 'k')
            .replace('w', 'v')
            .replace("x", "ks")
            .replace('y', 'j')

        val clean = Normalizer.normalize(latin, Normalizer.Form.NFC)
            .replace(Regex("[^a-zčćžšđ]+"), "")

        val out = ArrayList<String>(clean.length)
        var i = 0

        while (i < clean.length) {
            when {
                clean.startsWith("dž", i) -> {
                    out.add("dž")
                    i += 2
                }
                clean.startsWith("lj", i) -> {
                    out.add("lj")
                    i += 2
                }
                clean.startsWith("nj", i) -> {
                    out.add("nj")
                    i += 2
                }
                else -> {
                    out.add(clean[i].toString())
                    i++
                }
            }
        }

        return out
    }

    private fun weightedDistance(
        a: List<String>,
        b: List<String>
    ): Double {
        var previous = DoubleArray(b.size + 1) { it.toDouble() }
        var current = DoubleArray(b.size + 1)

        for (i in 1..a.size) {
            current[0] = i.toDouble()

            for (j in 1..b.size) {
                val delete = previous[j] + 1.0
                val insert = current[j - 1] + 1.0
                val substitute =
                    previous[j - 1] + substitutionCost(
                        a[i - 1],
                        b[j - 1]
                    )

                current[j] = minOf(
                    delete,
                    insert,
                    substitute
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.size]
    }

    private fun substitutionCost(
        a: String,
        b: String
    ): Double {
        if (a == b) return 0.0

        val pair = setOf(a, b)

        return when {
            pair == setOf("č", "ć") -> 0.18
            pair == setOf("dž", "đ") -> 0.20
            pair == setOf("s", "š") -> 0.30
            pair == setOf("z", "ž") -> 0.30
            pair == setOf("c", "č") -> 0.38
            pair == setOf("c", "ć") -> 0.38
            pair == setOf("l", "lj") -> 0.35
            pair == setOf("n", "nj") -> 0.35

            // Common voiced/unvoiced ASR confusions.
            pair == setOf("b", "p") -> 0.42
            pair == setOf("d", "t") -> 0.42
            pair == setOf("g", "k") -> 0.42
            pair == setOf("v", "f") -> 0.42
            pair == setOf("z", "s") -> 0.48
            pair == setOf("ž", "š") -> 0.48

            // Short-name vowel confusion should be tolerated a little,
            // but much less than consonant-neighbour confusions.
            a in vowels && b in vowels -> 0.62

            else -> 1.0
        }
    }

    private val vowels =
        setOf("a", "e", "i", "o", "u")
}
