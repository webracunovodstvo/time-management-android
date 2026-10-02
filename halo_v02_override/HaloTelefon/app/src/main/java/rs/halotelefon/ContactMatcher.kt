package rs.halotelefon

import kotlin.math.min

data class ContactCandidate(
    val contact: ContactPhone,
    val score: Double,
    val learnedUses: Int
)

class ContactMatcher(
    private val learningStore: LearningStore
) {
    fun rank(
        spoken: String,
        contacts: List<ContactPhone>,
        limit: Int = 3
    ): List<ContactCandidate> =
        rankBestOf(
            listOf(spoken),
            contacts,
            limit
        )

    fun rankBestOf(
        spokenForms: List<String>,
        contacts: List<ContactPhone>,
        limit: Int = 3
    ): List<ContactCandidate> {
        val forms =
            spokenForms
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        val learnedByForm =
            forms.associateWith {
                learningStore.usesForSpoken(it)
            }

        return contacts
            .map { contact ->
                var bestScore = 0.0
                var bestUses = 0

                for (spoken in forms) {
                    val base =
                        tokenAwareSimilarity(
                            spoken,
                            contact.displayName
                        )

                    val uses =
                        learnedByForm[spoken]
                            ?.get(contact.lookupKey)
                            ?: 0

                    val learnedBonus =
                        min(
                            0.20,
                            uses * 0.05
                        )

                    val total =
                        (base + learnedBonus)
                            .coerceAtMost(1.0)

                    if (total > bestScore) {
                        bestScore = total
                        bestUses = uses
                    }
                }

                ContactCandidate(
                    contact,
                    bestScore,
                    bestUses
                )
            }
            .sortedByDescending {
                it.score
            }
            .take(limit)
    }

    private fun tokenAwareSimilarity(
        spoken: String,
        displayName: String
    ): Double {
        val cleanName =
            displayName
                .replace(
                    Regex("[#@()\\[\\]{}.,;:_/\\\\|-]+"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        val nameTokens =
            cleanName
                .split(' ')
                .map { it.trim() }
                .filter { it.length >= 2 }

        val spokenTokens =
            spoken
                .replace(
                    Regex("[#@()\\[\\]{}.,;:_/\\\\|-]+"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()
                .split(' ')
                .map { it.trim() }
                .filter { it.length >= 2 }

        var best =
            combinedSimilarity(
                spoken,
                cleanName
            )

        if (
            spokenTokens.size >= 2 &&
            nameTokens.size >= 2
        ) {
            // Whisper can occasionally swap first/last name. Compare both
            // orders, but keep the complete two-word phrase dominant.
            val reversed =
                nameTokens
                    .asReversed()
                    .joinToString(" ")

            best =
                maxOf(
                    best,
                    combinedSimilarity(
                        spoken,
                        reversed
                    )
                )

            for (
                index in
                0 until nameTokens.lastIndex
            ) {
                val pair =
                    nameTokens[index] +
                        " " +
                        nameTokens[index + 1]

                val reversePair =
                    nameTokens[index + 1] +
                        " " +
                        nameTokens[index]

                best =
                    maxOf(
                        best,
                        combinedSimilarity(
                            spoken,
                            pair
                        ),
                        combinedSimilarity(
                            spoken,
                            reversePair
                        )
                    )
            }

            // A perfect first-name-only hit must not beat a much better
            // full-name match when the user actually said two words.
            for (token in nameTokens) {
                best =
                    maxOf(
                        best,
                        combinedSimilarity(
                            spoken,
                            token
                        ).coerceAtMost(0.82)
                    )
            }
        } else {
            // One spoken word: matching a first name or surname is valid.
            for (token in nameTokens) {
                best =
                    maxOf(
                        best,
                        combinedSimilarity(
                            spoken,
                            token
                        )
                    )
            }
        }

        return best
    }

    private fun combinedSimilarity(
        spoken: String,
        target: String
    ): Double {
        val orthographic =
            SerbianNormalizer.similarity(
                spoken,
                target
            )

        val phonetic =
            SerbianPhonetics.similarity(
                spoken,
                target
            )

        val spokenLength =
            SerbianPhonetics.phonemes(
                spoken
            ).size

        val weightedPhonetic =
            if (spokenLength <= 5) {
                phonetic
            } else {
                phonetic * 0.97
            }

        return maxOf(
            orthographic,
            weightedPhonetic
        ).coerceIn(0.0, 1.0)
    }
}
