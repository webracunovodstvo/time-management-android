package rs.halotelefon

import kotlin.math.min

data class ContactCandidate(
    val contact: ContactPhone,
    val score: Double,
    val learnedUses: Int
)

class ContactMatcher(private val learningStore: LearningStore) {
    fun rank(
        spoken: String,
        contacts: List<ContactPhone>,
        limit: Int = 3
    ): List<ContactCandidate> =
        rankBestOf(listOf(spoken), contacts, limit)

    fun rankBestOf(
        spokenForms: List<String>,
        contacts: List<ContactPhone>,
        limit: Int = 3
    ): List<ContactCandidate> {
        val forms = spokenForms
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return contacts
            .map { contact ->
                var bestScore = 0.0
                var bestUses = 0

                for (spoken in forms) {
                    val base = tokenAwareSimilarity(
                        spoken,
                        contact.displayName
                    )
                    val uses = learningStore.uses(
                        spoken,
                        contact.lookupKey
                    )
                    val learnedBonus =
                        min(0.20, uses * 0.05)
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
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun tokenAwareSimilarity(
        spoken: String,
        displayName: String
    ): Double {
        var best = SerbianNormalizer.similarity(
            spoken,
            displayName
        )

        val cleanName = displayName
            .replace(Regex("[#@()\\[\\]{}.,;:_/\\\\|-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = cleanName
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 }

        // Critical for short Serbian names: "Ana" should be a perfect/near
        // match for "Ana Petrović", not be penalized by the surname.
        for (token in tokens) {
            best = maxOf(
                best,
                SerbianNormalizer.similarity(
                    spoken,
                    token
                )
            )
        }

        // Also compare against adjacent name parts for cases where the user
        // says first+last name while the contact contains a prefix/suffix.
        if (tokens.size >= 2) {
            for (index in 0 until tokens.lastIndex) {
                val pair =
                    tokens[index] + " " +
                        tokens[index + 1]
                best = maxOf(
                    best,
                    SerbianNormalizer.similarity(
                        spoken,
                        pair
                    )
                )
            }
        }

        return best
    }
}
