package rs.halotelefon

import android.content.ContentResolver
import android.provider.ContactsContract
import java.util.Locale

data class ContactNumber(
    val number: String,
    val label: String,
    val isMobile: Boolean,
    val isPrimary: Boolean = false
)

data class ContactPhone(
    val lookupKey: String,
    val displayName: String,
    val number: String,
    val allNumbers: List<ContactNumber> = emptyList()
) {
    fun numbersForDisplay(): List<ContactNumber> =
        if (allNumbers.isNotEmpty()) {
            allNumbers
        } else {
            listOf(
                ContactNumber(
                    number = number,
                    label = "Broj",
                    isMobile = false,
                    isPrimary = true
                )
            )
        }

    fun numberSummary(
        compact: Boolean = false
    ): String {
        val numbers = numbersForDisplay()

        return numbers.joinToString(
            if (compact) "  •  " else "\n"
        ) { item ->
            val primaryMark =
                if (item.number == number) "✓ " else ""

            val shownNumber =
                if (compact) {
                    val digits =
                        item.number.filter(Char::isDigit)

                    if (digits.length > 4) {
                        "••• " + digits.takeLast(4)
                    } else {
                        item.number
                    }
                } else {
                    item.number
                }

            primaryMark +
                item.label +
                ": " +
                shownNumber
        }
    }
}

class ContactRepository(
    private val resolver: ContentResolver
) {
    private data class RawPhone(
        val lookupKey: String,
        val displayName: String,
        val number: String,
        val type: Int,
        val customLabel: String?,
        val isPrimary: Boolean,
        val order: Int
    )

    fun load(): List<ContactPhone> {
        val raw = ArrayList<RawPhone>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        )

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val lookupIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
                )
            val nameIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
            val numberIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
            val typeIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
            val labelIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
            val primaryIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                )
            val superPrimaryIndex =
                cursor.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
                )

            var order = 0

            while (cursor.moveToNext()) {
                val key =
                    cursor.getString(lookupIndex)
                        ?.trim()
                        .orEmpty()

                val name =
                    cursor.getString(nameIndex)
                        ?.trim()
                        .orEmpty()

                val number =
                    cursor.getString(numberIndex)
                        ?.trim()
                        .orEmpty()

                if (
                    key.isBlank() ||
                    name.isBlank() ||
                    number.isBlank()
                ) {
                    continue
                }

                val type =
                    cursor.getInt(typeIndex)

                val label =
                    cursor.getString(labelIndex)
                        ?.trim()

                val primary =
                    cursor.getInt(primaryIndex) == 1 ||
                        cursor.getInt(superPrimaryIndex) == 1

                raw += RawPhone(
                    lookupKey = key,
                    displayName = name,
                    number = number,
                    type = type,
                    customLabel = label,
                    isPrimary = primary,
                    order = order++
                )
            }
        }

        // One Android contact -> one candidate card.
        // Every phone row is preserved inside that contact.
        val grouped =
            raw.groupBy { it.lookupKey }

        val contacts =
            grouped.values.mapNotNull { rows ->
                val first =
                    rows.firstOrNull()
                        ?: return@mapNotNull null

                val deduped = LinkedHashMap<String, RawPhone>()

                for (row in rows) {
                    val normalized =
                        normalizeNumber(row.number)

                    val previous =
                        deduped[normalized]

                    if (
                        previous == null ||
                        numberPriority(row) >
                        numberPriority(previous)
                    ) {
                        deduped[normalized] = row
                    }
                }

                val orderedRows =
                    deduped.values.sortedWith(
                        compareByDescending<RawPhone> {
                            isMobile(it)
                        }.thenByDescending {
                            it.isPrimary
                        }.thenBy {
                            it.order
                        }
                    )

                if (orderedRows.isEmpty()) {
                    return@mapNotNull null
                }

                val contactNumbers =
                    orderedRows.map { row ->
                        ContactNumber(
                            number = row.number,
                            label = phoneLabel(row),
                            isMobile = isMobile(row),
                            isPrimary = row.isPrimary
                        )
                    }

                ContactPhone(
                    lookupKey = first.lookupKey,
                    displayName = first.displayName,
                    number = orderedRows.first().number,
                    allNumbers = contactNumbers
                )
            }

        // Some Samsung/Google/SIM sync combinations expose the same person
        // more than once with different lookup keys. Merge only when the
        // visible name is the same AND at least one phone number overlaps.
        return mergeMirroredContacts(contacts)
    }

    private fun mergeMirroredContacts(
        contacts: List<ContactPhone>
    ): List<ContactPhone> {
        val result = ArrayList<ContactPhone>()

        for (contact in contacts) {
            val normalizedName =
                SerbianNormalizer.normalize(
                    contact.displayName
                )

            val contactNumbers =
                contact.numbersForDisplay()
                    .map {
                        normalizeNumber(it.number)
                    }
                    .toSet()

            val index =
                result.indexOfFirst { existing ->
                    SerbianNormalizer.normalize(
                        existing.displayName
                    ) == normalizedName &&
                        existing.numbersForDisplay()
                            .any {
                                normalizeNumber(it.number) in
                                    contactNumbers
                            }
                }

            if (index < 0) {
                result += contact
                continue
            }

            val existing =
                result[index]

            val merged =
                (
                    existing.numbersForDisplay() +
                        contact.numbersForDisplay()
                    )
                    .distinctBy {
                        normalizeNumber(it.number)
                    }
                    .sortedWith(
                        compareByDescending<ContactNumber> {
                            it.isMobile
                        }.thenByDescending {
                            it.isPrimary
                        }
                    )

            result[index] =
                existing.copy(
                    number =
                        merged.firstOrNull()
                            ?.number
                            ?: existing.number,
                    allNumbers = merged
                )
        }

        return result
    }

    private fun numberPriority(
        row: RawPhone
    ): Int {
        var score = 0

        if (isMobile(row)) {
            score += 100
        }

        if (row.isPrimary) {
            score += 10
        }

        return score
    }

    private fun isMobile(
        row: RawPhone
    ): Boolean {
        if (
            row.type ==
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ||
            row.type ==
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE ||
            row.type ==
            ContactsContract.CommonDataKinds.Phone.TYPE_MMS
        ) {
            return true
        }

        val label =
            row.customLabel
                ?.lowercase(Locale.ROOT)
                .orEmpty()

        if (
            label.contains("mobil") ||
            label.contains("mobile") ||
            label.contains("cell")
        ) {
            return true
        }

        // Serbian mobile numbers: 06x... or +381 6x...
        val digits =
            row.number.filter(Char::isDigit)

        return digits.startsWith("06") ||
            digits.startsWith("3816")
    }

    private fun phoneLabel(
        row: RawPhone
    ): String {
        if (isMobile(row)) {
            return "Mobilni"
        }

        if (
            row.type ==
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM &&
            !row.customLabel.isNullOrBlank()
        ) {
            return row.customLabel
        }

        return when (row.type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME ->
                "Kuća"

            ContactsContract.CommonDataKinds.Phone.TYPE_WORK ->
                "Posao"

            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN ->
                "Glavni"

            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER ->
                "Drugi"

            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME ->
                "Faks kuća"

            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK ->
                "Faks posao"

            ContactsContract.CommonDataKinds.Phone.TYPE_PAGER ->
                "Pejdžer"

            else ->
                "Broj"
        }
    }

    private fun normalizeNumber(
        raw: String
    ): String =
        raw.filter(Char::isDigit)
            .takeLast(12)
}
