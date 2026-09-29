package rs.halotelefon

import kotlin.math.min

data class ContactCandidate(
    val contact: ContactPhone,
    val score: Double,
    val learnedUses: Int
)

class ContactMatcher(private val learningStore: LearningStore) {
    fun rank(spoken: String, contacts: List<ContactPhone>, limit: Int = 3): List<ContactCandidate> =
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
                var bestBase = 0.0
                var bestUses = 0
                for (spoken in forms) {
                    val base = SerbianNormalizer.similarity(spoken, contact.displayName)
                    val uses = learningStore.uses(spoken, contact.lookupKey)
                    val learnedBonus = min(0.20, uses * 0.05)
                    val total = (base + learnedBonus).coerceAtMost(1.0)
                    if (total > bestBase) {
                        bestBase = total
                        bestUses = uses
                    }
                }
                ContactCandidate(contact, bestBase, bestUses)
            }
            .sortedByDescending { it.score }
            .take(limit)
    }
}
